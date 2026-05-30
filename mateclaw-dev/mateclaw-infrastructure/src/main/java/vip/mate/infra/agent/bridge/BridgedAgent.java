package vip.mate.infra.agent.bridge;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import vip.mate.domain.agent.AgentService;
import vip.mate.domain.agent.AgentState;
import vip.mate.domain.agent.BaseAgent;
import vip.mate.domain.agent.StructuredStreamCapable;
import vip.mate.infra.agent.bridge.model.BridgeFrame;
import vip.mate.infra.agent.cli.LocalCliProcessManager;
import vip.mate.domain.agent.model.AgentEntity;
import vip.mate.domain.agent.repository.AgentMapper;
import vip.mate.domain.workspace.conversation.ConversationService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import vip.mate.domain.workspace.conversation.model.MessageEntity;

/**
 * 本地 Agent 桥接 — 通过 WebSocket 将聊天请求转发到本地 CLI Agent。
 *
 * <p>继承 BaseAgent 并实现 StructuredStreamCapable，
 * ChatController / AgentService / DelegateAgentTool 均无感知差异。
 */
@Slf4j
public class BridgedAgent extends BaseAgent implements StructuredStreamCapable {

    private final AgentBridgeManager bridgeManager;
    private final LocalCliProcessManager processManager;
    private final AgentMapper agentMapper;

    /** CLI type for local process agents: claude_code / open_code */
    private String cliType;

    /** WebSocket bridge constructor */
    public BridgedAgent(ConversationService conversationService,
                        AgentBridgeManager bridgeManager,
                        AgentMapper agentMapper) {
        super(null, conversationService);
        this.bridgeManager = bridgeManager;
        this.processManager = null;
        this.agentMapper = agentMapper;
    }

    /** Process bridge constructor */
    public BridgedAgent(ConversationService conversationService,
                        AgentBridgeManager bridgeManager,
                        LocalCliProcessManager processManager,
                        AgentMapper agentMapper) {
        super(null, conversationService);
        this.bridgeManager = bridgeManager;
        this.processManager = processManager;
        this.agentMapper = agentMapper;
    }

    public void setCliType(String cliType) {
        this.cliType = cliType;
    }

    @Override
    public Flux<AgentService.StreamDelta> chatStructuredStream(
            String userMessage, String conversationId) {

        // Prefer local process if available (claude_code / open_code)
        if (processManager != null) {
            return chatViaProcess(userMessage, conversationId);
        }

        // Fall back to WebSocket bridge
        if (!bridgeManager.isOnline(agentId)) {
            return Flux.error(new IllegalStateException(
                    "Local agent '" + agentName + "' is offline"));
        }

        BridgeFrame request = BridgeFrame.of("chat_request", Map.of(
                "message", userMessage,
                "conversationId", conversationId,
                "systemPrompt", systemPrompt != null ? systemPrompt : ""));

        return Flux.<AgentService.StreamDelta>create(sink -> {
            setState(AgentState.RUNNING);
            bridgeManager.registerResponseSink(agentId, sink);

            sink.onCancel(() -> {
                log.info("[BridgedAgent] Turn cancelled for agent={}", agentId);
                bridgeManager.send(agentId, BridgeFrame.of("stop_request", Map.of()))
                        .thenRun(() -> {})
                        .exceptionally(ex -> null);
                setState(AgentState.IDLE);
            });

            sink.onDispose(() -> {
                bridgeManager.unregisterResponseSink(agentId);
                setState(AgentState.IDLE);
            });

            bridgeManager.send(agentId, request)
                    .exceptionally(ex -> {
                        log.error("[BridgedAgent] Failed to send chat_request: {}",
                                ex.getMessage());
                        sink.error(ex);
                        return null;
                    });
        }, FluxSink.OverflowStrategy.LATEST);
    }

    private Flux<AgentService.StreamDelta> chatViaProcess(
            String userMessage, String conversationId) {
        return Flux.<AgentService.StreamDelta>create(sink -> {
            setState(AgentState.RUNNING);

            boolean spawned = processManager.spawn(
                    agentId, cliType, agentName, systemPrompt, null);

            if (!spawned && !processManager.isRunning(agentId)) {
                sink.error(new IllegalStateException(
                        "Failed to start local agent '" + agentName + "'"));
                return;
            }

            processManager.registerResponseSink(agentId, sink);

            sink.onCancel(() -> {
                processManager.sendFrame(agentId,
                        BridgeFrame.of("stop_request", Map.of()));
                setState(AgentState.IDLE);
            });

            sink.onDispose(() -> {
                processManager.unregisterResponseSink(agentId);
                setState(AgentState.IDLE);
            });

            // Build conversation context from recent history
            String contextualMessage = buildContextualMessage(userMessage, conversationId);

            String sessionId = processManager.getSessionId(agentId);
            Map<String, Object> chatPayload = new java.util.LinkedHashMap<>();
            chatPayload.put("message", contextualMessage);
            chatPayload.put("conversationId", conversationId);
            chatPayload.put("systemPrompt", systemPrompt != null ? systemPrompt : "");
            if (sessionId != null && !sessionId.isBlank()) {
                chatPayload.put("sessionId", sessionId);
            }
            BridgeFrame request = BridgeFrame.of("chat_request", chatPayload);

            try {
                processManager.sendFrame(agentId, request);
            } catch (Exception e) {
                log.error("[BridgedAgent] Failed to send chat_request to agent={}: {}",
                        agentId, e.getMessage());
                processManager.terminate(agentId);
                sink.error(e);
            }

        }, FluxSink.OverflowStrategy.LATEST);
    }

    /**
     * Build a contextual message by prepending recent conversation history
     * so the CLI agent has multi-turn context.
     */
    private String buildContextualMessage(String userMessage, String conversationId) {
        try {
            List<MessageEntity> history = conversationService.listRecentMessages(conversationId, 20);
            if (history == null || history.isEmpty()) {
                return userMessage;
            }

            StringBuilder ctx = new StringBuilder();
            ctx.append("# 以下是对话历史上下文\n\n");
            Map<Long, String> agentNameCache = new HashMap<>();
            for (MessageEntity msg : history) {
                String roleLabel;
                if ("user".equals(msg.getRole())) {
                    roleLabel = "用户";
                } else if (msg.getSenderAgentId() != null) {
                    roleLabel = agentNameCache.computeIfAbsent(msg.getSenderAgentId(), id -> {
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
            ctx.append("# 用户最新消息\n");
            ctx.append(userMessage);

            log.debug("[BridgedAgent] Built context for conversation={}, historyMsgs={}",
                    conversationId, history.size());
            return ctx.toString();
        } catch (Exception e) {
            log.warn("[BridgedAgent] Failed to build conversation context: {}", e.getMessage());
            return userMessage;
        }
    }

    @Override
    public String chat(String userMessage, String conversationId) {
        try {
            return chatStructuredStream(userMessage, conversationId)
                    .filter(delta -> !delta.isEvent() && delta.content() != null)
                    .map(AgentService.StreamDelta::content)
                    .collectList()
                    .map(list -> String.join("", list))
                    .block();
        } catch (Exception e) {
            log.error("[BridgedAgent] Synchronous chat failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public Flux<String> chatStream(String userMessage, String conversationId) {
        return chatStructuredStream(userMessage, conversationId)
                .filter(delta -> !delta.isEvent() && delta.content() != null)
                .map(AgentService.StreamDelta::content);
    }

    @Override
    public String execute(String goal, String conversationId) {
        return chat("Execute this goal: " + goal, conversationId);
    }
}
