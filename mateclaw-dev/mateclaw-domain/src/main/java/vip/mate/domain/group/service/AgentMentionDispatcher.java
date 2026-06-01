package vip.mate.domain.group.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;
import vip.mate.domain.agent.AgentService;
import vip.mate.infra.agent.bridge.model.BridgeFrame;
import vip.mate.infra.agent.cli.LocalCliProcessManager;
import vip.mate.domain.agent.model.AgentEntity;
import vip.mate.infra.channel.web.ChatStreamTracker;
import vip.mate.domain.workspace.conversation.ConversationService;
import vip.mate.domain.workspace.conversation.repository.MessageMapper;
import vip.mate.domain.workspace.core.repository.WorkspaceMapper;
import vip.mate.domain.workspace.core.model.WorkspaceEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intercepts the Orchestrator's streaming output to detect @AgentName: task lines,
 * spawns the named agent's CLI on-demand, and multiplexes agent responses into the SSE stream.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMentionDispatcher {

    private final GroupConversationService groupConversationService;
    private final ConversationService conversationService;
    private final AgentService agentService;
    private final LocalCliProcessManager processManager;
    private final ChatStreamTracker streamTracker;
    private final MessageMapper messageMapper;
    private final WorkspaceMapper workspaceMapper;

    /** Regex: @AgentName [after:DependencyAgent] task content */
    private static final Pattern AGENT_PATTERN = Pattern.compile("^@(\\S+)\\s+(?:\\[after:(\\S+)\\]\\s+)?(.+)$");

    /** Track active agent streams per conversation to avoid duplicate spawns */
    private final Map<String, Map<String, Boolean>> dispatchedAgents = new ConcurrentHashMap<>();

    /** Track active virtual threads so they can be interrupted on abort */
    private final Map<String, Thread> activeThreads = new ConcurrentHashMap<>();

    /** Collected DAG tasks for the current turn */
    private final Map<String, List<DagTask>> collectedTasks = new ConcurrentHashMap<>();

    /** Lock for DAG scheduling to prevent concurrent modification of TaskNode state */
    private final Object scheduleLock = new Object();

    enum TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, WAITING }

    record DagTask(String agentName, AgentEntity agent, String task,
                   String dependsOnAgentName, String claudeMdPath) {}

    static class TaskNode {
        final String agentName;
        final DagTask dagTask;
        volatile int inDegree;
        volatile TaskStatus status = TaskStatus.PENDING;
        final List<TaskNode> dependents = new ArrayList<>();

        TaskNode(String agentName, DagTask dagTask) {
            this.agentName = agentName;
            this.dagTask = dagTask;
        }
    }

    /**
     * Collect a line from the orchestrator output. If it matches @AgentName pattern,
     * parse and store the DagTask for later DAG-based execution.
     *
     * @param conversationDbId  database ID of the conversation (Long)
     * @param conversationId     string conversation ID
     * @param agentNameMap       name to AgentEntity lookup
     * @param line               a complete line from the orchestrator's output
     * @return true if the line was an @AgentName dispatch
     */
    public boolean collectLine(Long conversationDbId, String conversationId,
                               Map<String, AgentEntity> agentNameMap, String line) {
        Matcher m = AGENT_PATTERN.matcher(line.trim());
        if (!m.matches()) return false;

        String agentName = m.group(1);
        String afterAgent = m.group(2);  // null if no [after:XXX]
        String task = m.group(3).trim();

        // Dedup: don't collect the same agent twice in one turn
        Map<String, Boolean> convDispatched = dispatchedAgents.computeIfAbsent(
                conversationId, k -> new ConcurrentHashMap<>());
        if (convDispatched.putIfAbsent(agentName, Boolean.TRUE) != null) {
            log.info("[Dispatcher] Agent {} already collected in this turn, skipping", agentName);
            return true;
        }

        AgentEntity agent = agentNameMap.get(agentName);
        if (agent == null) {
            log.warn("[Dispatcher] @Agent name '{}' not found in group members", agentName);
            return true;
        }

        String claudeMdPath = groupConversationService.getClaudeMdPath(conversationDbId, agentName);

        collectedTasks.computeIfAbsent(conversationId, k -> new ArrayList<>())
                .add(new DagTask(agentName, agent, task, afterAgent, claudeMdPath));

        log.info("[Dispatcher] Collected task: agent={}, dependsOn={}, task={}",
                agentName, afterAgent != null ? afterAgent : "none",
                task.length() > 80 ? task.substring(0, 80) + "..." : task);
        return true;
    }

    /**
     * Build the DAG from collected tasks and start scheduling.
     * Called once after the orchestrator stream completes.
     */
    public void executeCollected(String conversationId, Semaphore semaphore) {
        List<DagTask> tasks = collectedTasks.remove(conversationId);
        if (tasks == null || tasks.isEmpty()) return;

        int total = tasks.size();

        // Pre-increment flux count for all tasks so "done" doesn't fire early
        for (int i = 0; i < total; i++) {
            streamTracker.incrementFlux(conversationId);
        }

        // Build node map: agentName -> TaskNode
        Map<String, TaskNode> nodeMap = new LinkedHashMap<>();
        for (DagTask dt : tasks) {
            nodeMap.put(dt.agentName, new TaskNode(dt.agentName, dt));
        }

        // Build dependency edges
        for (TaskNode node : nodeMap.values()) {
            String dependsOn = node.dagTask.dependsOnAgentName;
            if (dependsOn == null) continue;
            TaskNode upstream = nodeMap.get(dependsOn);
            if (upstream == null) {
                log.warn("[Dispatcher] Dependency '{}' not found for '{}', treating as independent",
                        dependsOn, node.agentName);
                continue;
            }
            node.inDegree++;
            upstream.dependents.add(node);
        }

        // Cycle detection: if every node has inDegree > 0, there's a cycle
        boolean hasReady = nodeMap.values().stream().anyMatch(n -> n.inDegree == 0);
        if (!hasReady) {
            log.error("[Dispatcher] Circular dependency detected in group chat plan");
            // Degrade: treat all as independent
            nodeMap.values().forEach(n -> { n.inDegree = 0; n.dependents.clear(); });
        }

        // Broadcast start events for all tasks
        for (TaskNode node : nodeMap.values()) {
            Map<String, Object> startEvent = new LinkedHashMap<>();
            startEvent.put("agentName", node.agentName);
            startEvent.put("agentId", String.valueOf(node.dagTask.agent.getId()));
            startEvent.put("taskDescription", node.dagTask.task);
            if (node.dagTask.dependsOnAgentName != null) {
                startEvent.put("dependsOn", node.dagTask.dependsOnAgentName);
            }
            streamTracker.broadcastObject(conversationId, "agent_message_start", startEvent);
        }

        log.info("[Dispatcher] DAG built: {} nodes, ready={}",
                nodeMap.size(), nodeMap.values().stream().filter(n -> n.inDegree == 0).count());

        // Schedule nodes with no dependencies
        scheduleReady(new ArrayList<>(nodeMap.values()), conversationId, semaphore);
    }

    private void scheduleReady(List<TaskNode> allNodes, String conversationId, Semaphore semaphore) {
        synchronized (scheduleLock) {
            for (TaskNode node : allNodes) {
                if (node.status != TaskStatus.PENDING || node.inDegree > 0) continue;
                scheduleNode(node, conversationId, semaphore);
            }
        }
    }

    private void scheduleNode(TaskNode node, String conversationId, Semaphore semaphore) {
        node.status = TaskStatus.RUNNING;

        log.info("[Dispatcher] Starting agent={}, inDegree was 0", node.agentName);

        streamTracker.broadcastObject(conversationId, "delegation_progress", Map.of(
                "agentName", node.agentName,
                "agentId", node.dagTask.agent.getId(),
                "status", "running"
        ));

        Thread vt = Thread.startVirtualThread(() -> {
            try {
                if (!semaphore.tryAcquire(180, TimeUnit.SECONDS)) {
                    log.warn("[Dispatcher] Semaphore timeout for agent={}", node.agentName);
                    node.status = TaskStatus.FAILED;
                    streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                            "agentName", node.agentName,
                            "status", "error",
                            "error", "等待槽位超时"
                    ));
                    handleNodeCompletion(node, conversationId, semaphore);
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                node.status = TaskStatus.FAILED;
                handleNodeCompletion(node, conversationId, semaphore);
                return;
            }

            try {
                activeThreads.put(conversationId + ":" + node.agentName, Thread.currentThread());
                executeSingleNode(node, conversationId, () ->
                        handleNodeCompletion(node, conversationId, semaphore));
            } finally {
                activeThreads.remove(conversationId + ":" + node.agentName);
                semaphore.release();
            }
        });
        activeThreads.put(conversationId + ":" + node.agentName, vt);
    }

    private void handleNodeCompletion(TaskNode node, String conversationId, Semaphore semaphore) {
        synchronized (scheduleLock) {
            if (node.status == TaskStatus.COMPLETED) {
                // Decrement inDegree of dependents; schedule if ready
                for (TaskNode dependent : node.dependents) {
                    dependent.inDegree--;
                    if (dependent.inDegree == 0 && dependent.status == TaskStatus.PENDING) {
                        scheduleNode(dependent, conversationId, semaphore);
                    }
                }
            } else {
                // Upstream failed: mark all transitive dependents as WAITING
                java.util.ArrayDeque<TaskNode> queue = new java.util.ArrayDeque<>();
                queue.add(node);
                while (!queue.isEmpty()) {
                    TaskNode failed = queue.poll();
                    for (TaskNode dependent : failed.dependents) {
                        if (dependent.status == TaskStatus.PENDING) {
                            dependent.status = TaskStatus.WAITING;
                            streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                                    "agentName", dependent.agentName,
                                    "status", "waiting",
                                    "dependsOn", failed.agentName
                            ));
                            // Decrement flux counter for the waiting node
                            streamTracker.completeAndConsumeIfLast(conversationId);
                            queue.add(dependent);
                        }
                    }
                }
            }

            // Decrement flux counter for the completed/failed node
            ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
            if (cr.allDone()) {
                streamTracker.broadcastObject(conversationId, "done", Map.of(
                        "conversationId", conversationId,
                        "status", "completed"
                ));
            }
        }
    }

    private void executeSingleNode(TaskNode node, String conversationId, Runnable onComplete) {
        AgentEntity agent = node.dagTask.agent;
        String agentName = node.agentName;
        String task = node.dagTask.task;
        String claudeMdPath = node.dagTask.claudeMdPath;
        String agentIdStr = String.valueOf(agent.getId());
        StringBuilder fullResponse = new StringBuilder();

        try {
            String workingDir = resolveWorkingDir(conversationId);
            boolean spawned = processManager.spawn(
                    agentIdStr, agent.getCliType(), agentName,
                    agent.getSystemPrompt(), claudeMdPath, workingDir);

            if (!spawned && !processManager.isRunning(agentIdStr)) {
                log.error("[Dispatcher] Failed to spawn CLI for agent={}", agentName);
                node.status = TaskStatus.FAILED;
                streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                        "agentName", agentName,
                        "status", "error",
                        "error", "Agent CLI 启动失败"
                ));
                onComplete.run();
                return;
            }

            Flux.<AgentService.StreamDelta>create(sink -> {
                processManager.registerResponseSink(agentIdStr, sink);
                sink.onDispose(() -> {
                    processManager.unregisterResponseSink(agentIdStr);
                    processManager.terminate(agentIdStr);
                });

                java.util.LinkedHashMap<String, Object> chatPayload = new java.util.LinkedHashMap<>();
                chatPayload.put("message", task);
                chatPayload.put("conversationId", conversationId);
                chatPayload.put("systemPrompt", agent.getSystemPrompt() != null ? agent.getSystemPrompt() : "");
                BridgeFrame request = BridgeFrame.of("chat_request", chatPayload);
                processManager.sendFrame(agentIdStr, request);
            }, FluxSink.OverflowStrategy.BUFFER)
            .publishOn(Schedulers.boundedElastic())
            .doOnNext(delta -> {
                if (delta.content() != null) {
                    fullResponse.append(delta.content());
                    streamTracker.broadcastObject(conversationId, "content_delta", Map.of(
                            "delta", delta.content(),
                            "agentName", agentName
                    ));
                }
            })
            .doOnComplete(() -> {
                String responseText = fullResponse.toString();
                if (!responseText.isBlank()) {
                    try {
                        var msg = conversationService.saveMessage(conversationId, "assistant", responseText);
                        msg.setSenderAgentId(agent.getId());
                        try {
                            messageMapper.updateById(msg);
                        } catch (Exception e) {
                            log.error("[Dispatcher] Failed to set senderAgentId on msg {}: {}", msg.getId(), e.getMessage());
                        }
                    } catch (Exception e) {
                        log.error("[Dispatcher] Failed to save message for agent={}: {}", agentName, e.getMessage());
                    }
                }
                processManager.terminate(agentIdStr);
                node.status = TaskStatus.COMPLETED;
                streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                        "agentName", agentName,
                        "status", "completed"
                ));
                log.info("[Dispatcher] Agent {} completed, response length={}", agentName, responseText.length());
                onComplete.run();
            })
            .doOnError(err -> {
                log.error("[Dispatcher] Agent {} error: {}", agentName, err.getMessage());
                processManager.terminate(agentIdStr);
                node.status = TaskStatus.FAILED;
                streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                        "agentName", agentName,
                        "status", "error",
                        "error", err.getMessage()
                ));
                onComplete.run();
            })
            .subscribe();

        } catch (Exception e) {
            log.error("[Dispatcher] Failed to spawn agent {}: {}", agentName, e.getMessage());
            node.status = TaskStatus.FAILED;
            streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                    "agentName", agentName,
                    "status", "error",
                    "error", e.getMessage()
            ));
            onComplete.run();
        }
    }

    private String resolveWorkingDir(String conversationId) {
        if (workspaceMapper == null) return null;
        try {
            var conv = conversationService.getByConversationId(conversationId);
            if (conv == null || conv.getWorkspaceId() == null) return null;
            WorkspaceEntity ws = workspaceMapper.selectById(conv.getWorkspaceId());
            if (ws == null) return null;
            String basePath = ws.getBasePath();
            return (basePath != null && !basePath.isBlank()) ? basePath : null;
        } catch (Exception e) {
            log.warn("[Dispatcher] Failed to resolve working dir: {}", e.getMessage());
            return null;
        }
    }

    /** Reset dispatch tracking for a new turn */
    public void resetForTurn(String conversationId) {
        dispatchedAgents.remove(conversationId);
        collectedTasks.remove(conversationId);
        // Interrupt any active dispatch threads for this conversation
        String prefix = conversationId + ":";
        activeThreads.forEach((key, thread) -> {
            if (key.startsWith(prefix)) {
                thread.interrupt();
            }
        });
        activeThreads.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
    }
}
