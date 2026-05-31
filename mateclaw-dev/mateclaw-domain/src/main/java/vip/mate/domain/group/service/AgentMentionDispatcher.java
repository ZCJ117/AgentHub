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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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

    /** Regex: @AgentName task content */
    private static final Pattern AGENT_PATTERN = Pattern.compile("^@(\\S+)\\s+(.+)$");

    /** Track active agent streams per conversation to avoid duplicate spawns */
    private final Map<String, Map<String, Boolean>> dispatchedAgents = new ConcurrentHashMap<>();

    /** Track active virtual threads so they can be interrupted on abort */
    private final Map<String, Thread> activeThreads = new ConcurrentHashMap<>();

    /**
     * Test a complete line against the @AgentName pattern.
     * If matched, spawn the agent and stream its response.
     *
     * @param conversationDbId  database ID of the conversation (Long)
     * @param conversationId     string conversation ID
     * @param agentNameMap       name to AgentEntity lookup
     * @param line               a complete line from the orchestrator's output
     * @param semaphore          concurrency limiter
     * @return true if the line was an @AgentName dispatch
     */
    public boolean dispatchIfComplete(Long conversationDbId, String conversationId,
                                       Map<String, AgentEntity> agentNameMap,
                                       String line, Semaphore semaphore) {
        Matcher m = AGENT_PATTERN.matcher(line.trim());
        if (!m.matches()) return false;

        String agentName = m.group(1);
        String task = m.group(2).trim();

        // Dedup: don't spawn the same agent twice in one turn
        Map<String, Boolean> convDispatched = dispatchedAgents.computeIfAbsent(
                conversationId, k -> new ConcurrentHashMap<>());
        if (convDispatched.putIfAbsent(agentName, Boolean.TRUE) != null) {
            log.info("[Dispatcher] Agent {} already dispatched in this turn, skipping", agentName);
            return true;
        }

        AgentEntity agent = agentNameMap.get(agentName);
        if (agent == null) {
            log.warn("[Dispatcher] @Agent name '{}' not found in group members", agentName);
            return true;
        }

        String claudeMdPath = groupConversationService.getClaudeMdPath(conversationDbId, agentName);

        // Broadcast agent_message_start
        streamTracker.broadcastObject(conversationId, "agent_message_start", Map.of(
                "agentName", agentName,
                "agentId", String.valueOf(agent.getId()),
                "taskDescription", task
        ));

        // Increment flux count BEFORE starting virtual thread
        // so orchestrator's completeAndConsumeIfLast sees this sub-agent
        streamTracker.incrementFlux(conversationId);

        // Run agent in a virtual thread so orchestrator stream is not blocked
        Thread vt = Thread.startVirtualThread(() -> {
            try {
                if (!semaphore.tryAcquire(180, TimeUnit.SECONDS)) {
                    log.warn("[Dispatcher] Semaphore timeout for agent={}", agentName);
                    broadcastAgentError(conversationId, agentName, "等待槽位超时");
                    completeAndBroadcastDoneIfLast(conversationId);
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                completeAndBroadcastDoneIfLast(conversationId);
                return;
            }

            try {
                spawnAndStreamAgent(agent, agentName, task, claudeMdPath, conversationId);
            } finally {
                activeThreads.remove(conversationId + ":" + agentName);
                semaphore.release();
            }
        });
        activeThreads.put(conversationId + ":" + agentName, vt);

        return true;
    }

    private void spawnAndStreamAgent(AgentEntity agent, String agentName, String task,
                                      String claudeMdPath, String conversationId) {
        String agentIdStr = String.valueOf(agent.getId());
        StringBuilder fullResponse = new StringBuilder();

        try {
            String workingDir = resolveWorkingDir(conversationId);
            boolean spawned = processManager.spawn(
                    agentIdStr, agent.getCliType(), agentName,
                    agent.getSystemPrompt(), claudeMdPath, workingDir);

            if (!spawned && !processManager.isRunning(agentIdStr)) {
                log.error("[Dispatcher] Failed to spawn CLI for agent={}", agentName);
                broadcastAgentError(conversationId, agentName, "Agent CLI 启动失败");
                completeAndBroadcastDoneIfLast(conversationId);
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
                            log.error("[Dispatcher] Failed to set senderAgentId on message {}: {}", msg.getId(), e.getMessage());
                        }
                    } catch (Exception e) {
                        log.error("[Dispatcher] Failed to save message for agent={}: {}", agentName, e.getMessage());
                    }
                }
                streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                        "agentName", agentName,
                        "status", "completed"
                ));
                processManager.terminate(agentIdStr);
                log.info("[Dispatcher] Agent {} completed, response length={}", agentName, responseText.length());
                ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                if (cr.allDone()) {
                    streamTracker.broadcastObject(conversationId, "done", Map.of(
                            "conversationId", conversationId,
                            "status", "completed"
                    ));
                }
            })
            .doOnError(err -> {
                log.error("[Dispatcher] Agent {} error: {}", agentName, err.getMessage());
                broadcastAgentError(conversationId, agentName, err.getMessage());
                processManager.terminate(agentIdStr);
                ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                if (cr.allDone()) {
                    streamTracker.broadcastObject(conversationId, "done", Map.of(
                            "conversationId", conversationId,
                            "status", "error",
                            "error", err.getMessage()
                    ));
                }
            })
            .subscribe();

        } catch (Exception e) {
            log.error("[Dispatcher] Failed to spawn agent {}: {}", agentName, e.getMessage());
            broadcastAgentError(conversationId, agentName, e.getMessage());
            completeAndBroadcastDoneIfLast(conversationId);
        }
    }

    private void broadcastAgentError(String conversationId, String agentName, String error) {
        streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                "agentName", agentName,
                "status", "error",
                "error", error
        ));
    }

    private void completeAndBroadcastDoneIfLast(String conversationId) {
        ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
        if (cr.allDone()) {
            streamTracker.broadcastObject(conversationId, "done", Map.of(
                    "conversationId", conversationId,
                    "status", "completed"
            ));
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
