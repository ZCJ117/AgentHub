package vip.mate.agent.bridge;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import vip.mate.agent.AgentService;
import vip.mate.agent.AgentState;
import vip.mate.agent.BaseAgent;
import vip.mate.agent.StructuredStreamCapable;
import vip.mate.agent.bridge.model.BridgeFrame;
import vip.mate.agent.cli.LocalCliProcessManager;
import vip.mate.workspace.conversation.ConversationService;

import java.util.Map;

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

    /** CLI type for local process agents: claude_code / open_code */
    private String cliType;

    /** WebSocket bridge constructor */
    public BridgedAgent(ConversationService conversationService,
                        AgentBridgeManager bridgeManager) {
        super(null, conversationService);
        this.bridgeManager = bridgeManager;
        this.processManager = null;
    }

    /** Process bridge constructor */
    public BridgedAgent(ConversationService conversationService,
                        AgentBridgeManager bridgeManager,
                        LocalCliProcessManager processManager) {
        super(null, conversationService);
        this.bridgeManager = bridgeManager;
        this.processManager = processManager;
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
                    agentId, cliType, agentName, systemPrompt);

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

            BridgeFrame request = BridgeFrame.of("chat_request", Map.of(
                    "message", userMessage,
                    "conversationId", conversationId,
                    "systemPrompt", systemPrompt != null ? systemPrompt : ""));

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
