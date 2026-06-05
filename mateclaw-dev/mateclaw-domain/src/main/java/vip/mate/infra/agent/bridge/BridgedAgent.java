package vip.mate.infra.agent.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import vip.mate.domain.workspace.conversation.model.MessageContentPart;
import vip.mate.domain.agent.AgentService;
import vip.mate.domain.agent.AgentState;
import vip.mate.domain.agent.BaseAgent;
import vip.mate.domain.agent.StructuredStreamCapable;
import vip.mate.infra.agent.bridge.model.BridgeFrame;
import vip.mate.infra.agent.cli.LocalCliProcessManager;
import vip.mate.domain.agent.model.AgentEntity;
import vip.mate.domain.agent.repository.AgentMapper;
import vip.mate.domain.workspace.conversation.ConversationService;
import vip.mate.domain.workspace.core.repository.WorkspaceMapper;
import vip.mate.domain.workspace.core.model.WorkspaceEntity;
import vip.mate.domain.workspace.conversation.model.ConversationEntity;

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
    private final WorkspaceMapper workspaceMapper;

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
        this.workspaceMapper = null;
    }

    /** Process bridge constructor */
    public BridgedAgent(ConversationService conversationService,
                        AgentBridgeManager bridgeManager,
                        LocalCliProcessManager processManager,
                        AgentMapper agentMapper,
                        WorkspaceMapper workspaceMapper) {
        super(null, conversationService);
        this.bridgeManager = bridgeManager;
        this.processManager = processManager;
        this.agentMapper = agentMapper;
        this.workspaceMapper = workspaceMapper;
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

            String workingDir = resolveWorkingDir(conversationId);
            boolean spawned = processManager.spawn(
                    agentId, cliType, agentName, systemPrompt, null, workingDir);

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
            // ── 新增: 提取用户上传的文件列表 ──
            ObjectMapper objectMapper = new ObjectMapper();
            List<MessageEntity> fileMessages = conversationService.listRecentMessages(conversationId, 50);
            List<String> fileLines = new ArrayList<>();
            for (MessageEntity msg : fileMessages) {
                if ("user".equals(msg.getRole()) && msg.getContentParts() != null) {
                    try {
                        List<MessageContentPart> parts = objectMapper.readValue(
                            msg.getContentParts(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, MessageContentPart.class)
                        );
                        for (MessageContentPart part : parts) {
                            if ("file".equals(part.getType()) && part.getFileName() != null) {
                                java.nio.file.Path absPath = part.getPath() != null
                                    ? java.nio.file.Paths.get(part.getPath()).toAbsolutePath().normalize()
                                    : null;
                                fileLines.add("- " + part.getFileName() + ": "
                                    + (absPath != null ? absPath.toString() : part.getPath()));
                            }
                        }
                    } catch (Exception ignored) { }
                }
            }
            if (!fileLines.isEmpty()) {
                ctx.append("[用户上传的文件]\n");
                for (String line : fileLines) {
                    ctx.append(line).append("\n");
                }
                ctx.append("\n");
            }
            // ── 文件列表结束 ──
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

    private String resolveWorkingDir(String conversationId) {
        if (workspaceMapper == null) return null;
        try {
            ConversationEntity conv = conversationService.getByConversationId(conversationId);
            if (conv == null || conv.getWorkspaceId() == null) return null;
            WorkspaceEntity ws = workspaceMapper.selectById(conv.getWorkspaceId());
            if (ws == null) return null;
            String basePath = ws.getBasePath();
            return (basePath != null && !basePath.isBlank()) ? basePath : null;
        } catch (Exception e) {
            log.warn("[BridgedAgent] Failed to resolve working dir: {}", e.getMessage());
            return null;
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
