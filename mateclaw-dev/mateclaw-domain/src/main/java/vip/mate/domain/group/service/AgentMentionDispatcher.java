package vip.mate.domain.group.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;
import vip.mate.domain.agent.AgentService;
import vip.mate.domain.agent.repository.AgentMapper;
import vip.mate.infra.agent.bridge.model.BridgeFrame;
import vip.mate.infra.agent.cli.LocalCliProcessManager;
import vip.mate.domain.agent.model.AgentEntity;
import vip.mate.infra.channel.web.ChatStreamTracker;
import vip.mate.domain.workspace.conversation.ConversationService;
import vip.mate.domain.workspace.conversation.repository.MessageMapper;
import vip.mate.domain.workspace.conversation.model.MessageEntity;
import vip.mate.domain.workspace.core.repository.WorkspaceMapper;
import vip.mate.domain.workspace.core.model.WorkspaceEntity;

import java.util.ArrayList;
import java.util.HashMap;
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
    private final AgentMapper agentMapper;
    private final GroupOrchestratorService groupOrchestratorService;

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

    /** Active DAGs during pause — keyed by conversationId, survives between HTTP requests */
    private final Map<String, DagState> activeDags = new ConcurrentHashMap<>();

    record DagState(Map<String, TaskNode> nodeMap, Semaphore semaphore,
                    String originalTaskMessage, String username) {}

    enum TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, WAITING, READY }

    record DagTask(String agentName, AgentEntity agent, String task,
                   String dependsOnAgentName, String claudeMdPath) {}

    static class TaskNode {
        final String agentName;
        final DagTask dagTask;
        volatile int inDegree;
        volatile TaskStatus status = TaskStatus.PENDING;
        final List<TaskNode> dependents = new ArrayList<>();
        volatile Long placeholderMessageId;

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
    public void executeCollected(String conversationId, Semaphore semaphore,
                                 String originalTaskMessage, String username) {
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

        // Cycle detection using Kahn's algorithm (copy in-degrees)
        Map<TaskNode, Integer> workingDegrees = new LinkedHashMap<>();
        for (TaskNode n : nodeMap.values()) workingDegrees.put(n, n.inDegree);

        java.util.ArrayDeque<TaskNode> zeroQueue = new java.util.ArrayDeque<>();
        for (TaskNode n : nodeMap.values()) {
            if (workingDegrees.get(n) == 0) zeroQueue.add(n);
        }

        int processed = 0;
        while (!zeroQueue.isEmpty()) {
            TaskNode n = zeroQueue.poll();
            processed++;
            for (TaskNode dep : n.dependents) {
                int deg = workingDegrees.get(dep) - 1;
                workingDegrees.put(dep, deg);
                if (deg == 0) zeroQueue.add(dep);
            }
        }

        if (processed < nodeMap.size()) {
            log.error("[Dispatcher] Circular dependency detected ({} of {} nodes reachable), degrading to all-parallel",
                    processed, nodeMap.size());
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

        activeDags.put(conversationId, new DagState(nodeMap, semaphore, originalTaskMessage, username));
    }

    /**
     * Continue a READY node after user confirmation.
     * Idempotent: only acts on nodes with READY status.
     */
    public void continueNode(String conversationId, String agentName, Semaphore semaphore) {
        DagState state = activeDags.get(conversationId);
        if (state == null) {
            log.warn("[Dispatcher] continueNode: no active DAG for conversation={}", conversationId);
            return;
        }
        TaskNode node = state.nodeMap.get(agentName);
        if (node == null) {
            log.warn("[Dispatcher] continueNode: agent '{}' not found in DAG", agentName);
            return;
        }
        synchronized (scheduleLock) {
            if (node.status != TaskStatus.READY) {
                log.info("[Dispatcher] continueNode: agent '{}' status={}, not READY, skipping", agentName, node.status);
                return;
            }
            Semaphore sem = semaphore != null ? semaphore : state.semaphore;
            // Remove the placeholder message saved when node was marked READY
            if (node.placeholderMessageId != null) {
                try { messageMapper.deleteById(node.placeholderMessageId); } catch (Exception ignored) {}
                node.placeholderMessageId = null;
            }
            scheduleNode(node, conversationId, sem);
        }
    }

    /** Check if a paused DAG exists for this conversation */
    public boolean hasActiveDag(String conversationId) {
        return activeDags.containsKey(conversationId);
    }

    /**
     * Directly spawn a single agent without DAG collection.
     * Used during DAG pause for ad-hoc @mentions to completed agents.
     *
     * @return true if the line was an @AgentName dispatch
     */
    public boolean spawnSingleAgent(Long conversationDbId, String conversationId,
                                     Map<String, AgentEntity> agentNameMap, String line,
                                     Semaphore semaphore) {
        Matcher m = AGENT_PATTERN.matcher(line.trim());
        if (!m.matches()) return false;

        String agentName = m.group(1);
        String task = m.group(3) != null ? m.group(3).trim() : "";

        AgentEntity agent = agentNameMap.get(agentName);
        if (agent == null) {
            log.warn("[Dispatcher] spawnSingle: agent '{}' not found", agentName);
            return false;
        }

        String claudeMdPath = groupConversationService.getClaudeMdPath(conversationDbId, agentName);

        streamTracker.incrementFlux(conversationId);

        DagTask dagTask = new DagTask(agentName, agent, task, null, claudeMdPath);
        TaskNode node = new TaskNode(agentName, dagTask);

        streamTracker.broadcastObject(conversationId, "agent_message_start", Map.of(
                "agentName", agentName,
                "agentId", String.valueOf(agent.getId()),
                "taskDescription", task
        ));

        scheduleNode(node, conversationId, semaphore, () -> {
            ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
            if (cr.allDone()) {
                streamTracker.broadcastObject(conversationId, "done", Map.of(
                        "conversationId", conversationId,
                        "status", "completed"
                ));
            }
        });

        return true;
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
        scheduleNode(node, conversationId, semaphore, null);
    }

    private void scheduleNode(TaskNode node, String conversationId, Semaphore semaphore, Runnable customOnComplete) {
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
                            "agentName", node.agentName, "status", "error", "error", "等待槽位超时"
                    ));
                    if (customOnComplete != null) customOnComplete.run();
                    else handleNodeCompletion(node, conversationId, semaphore);
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                node.status = TaskStatus.FAILED;
                if (customOnComplete != null) customOnComplete.run();
                else handleNodeCompletion(node, conversationId, semaphore);
                return;
            }

            activeThreads.put(conversationId + ":" + node.agentName, Thread.currentThread());
            try {
                executeSingleNode(node, conversationId, () -> {
                    if (customOnComplete != null) customOnComplete.run();
                    else handleNodeCompletion(node, conversationId, semaphore);
                });
            } finally {
                activeThreads.remove(conversationId + ":" + node.agentName);
                semaphore.release();
            }
        });
    }

    private void handleNodeCompletion(TaskNode node, String conversationId, Semaphore semaphore) {
        synchronized (scheduleLock) {
            if (node.status == TaskStatus.COMPLETED) {
                // Decrement inDegree of dependents; mark READY instead of auto-scheduling
                boolean pausedAny = false;
                for (TaskNode dependent : node.dependents) {
                    if (dependent.inDegree > 0) dependent.inDegree--;
                    if (dependent.inDegree == 0 && dependent.status == TaskStatus.PENDING) {
                        dependent.status = TaskStatus.READY;
                        streamTracker.broadcastObject(conversationId, "agent_ready", Map.of(
                                "agentName", dependent.agentName,
                                "agentId", String.valueOf(dependent.dagTask.agent.getId()),
                                "dependsOn", node.agentName,
                                "taskDescription", dependent.dagTask.task
                        ));
                        log.info("[Dispatcher] Agent {} marked READY, waiting for user confirmation", dependent.agentName);
                        // Save a placeholder message so READY state persists across page refreshes
                        try {
                            var placeholder = conversationService.saveMessage(conversationId, "assistant", "");
                            placeholder.setSenderAgentId(dependent.dagTask.agent.getId());
                            placeholder.setStatus("ready");
                            messageMapper.updateById(placeholder);
                            dependent.placeholderMessageId = placeholder.getId();
                        } catch (Exception e) {
                            log.warn("[Dispatcher] Failed to save READY placeholder for {}: {}", dependent.agentName, e.getMessage());
                        }
                        pausedAny = true;
                    }
                }
                // Notify frontend to unlock composer while DAG is paused
                if (pausedAny) {
                    streamTracker.broadcastObject(conversationId, "dag_paused", Map.of(
                            "conversationId", conversationId,
                            "message", "子任务已就绪，等待确认"
                    ));
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

    /**
     * Build a contextual message by prepending recent conversation history
     * so the sub-agent has multi-turn context in group chat.
     */
    private String buildContextMessage(String task, String conversationId) {
        try {
            List<MessageEntity> history = conversationService.listRecentMessages(conversationId, 20);
            if (history == null || history.isEmpty()) {
                return task;
            }

            StringBuilder ctx = new StringBuilder();
            ctx.append("# 以下是群聊对话历史上下文\n\n");
            Map<Long, String> nameCache = new HashMap<>();
            for (MessageEntity msg : history) {
                String roleLabel;
                if ("user".equals(msg.getRole())) {
                    roleLabel = "用户";
                } else if (msg.getSenderAgentId() != null) {
                    roleLabel = nameCache.computeIfAbsent(msg.getSenderAgentId(), id -> {
                        if (agentMapper != null) {
                            AgentEntity ag = agentMapper.selectById(id);
                            return ag != null ? ag.getName() : "Agent#" + id;
                        }
                        return "Agent#" + id;
                    });
                } else {
                    roleLabel = "AI助手";
                }
                String content = conversationService.renderMessageContent(msg);
                if (content != null && !content.isBlank()) {
                    ctx.append(roleLabel).append(": ").append(content).append("\n\n");
                }
            }
            ctx.append("---\n");
            ctx.append("# 当前任务\n");
            ctx.append(task);

            log.debug("[Dispatcher] Built context for conversation={}, historyMsgs={}, totalChars={}",
                    conversationId, history.size(), ctx.length());
            return ctx.toString();
        } catch (Exception e) {
            log.warn("[Dispatcher] Failed to build conversation context: {}", e.getMessage());
            return task;
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

                String contextualMessage = buildContextMessage(task, conversationId);
                java.util.LinkedHashMap<String, Object> chatPayload = new java.util.LinkedHashMap<>();
                chatPayload.put("message", contextualMessage);
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
                try { processManager.terminate(agentIdStr); } catch (Exception ignored) {}
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
                try { processManager.terminate(agentIdStr); } catch (Exception ignored) {}
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
        activeDags.remove(conversationId);
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
