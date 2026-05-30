package vip.mate.infra.agent.bridge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import vip.mate.domain.agent.AgentService;
import vip.mate.infra.agent.bridge.model.BridgeFrame;
import vip.mate.infra.agent.bridge.model.BridgeSession;

import java.security.Principal;
import java.util.Map;

/**
 * Agent Bridge WebSocket Handler。
 *
 * <p>每个 WS session 有独立线程，并发安全由 AgentBridgeManager 保证。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentBridgeWsHandler extends TextWebSocketHandler {

    private final AgentBridgeManager bridgeManager;
    private final AgentBridgeProtocol protocol;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long workspaceId = 1L;
        Long userId = 0L;

        Principal principal = session.getPrincipal();
        if (principal != null) {
            Object uid = session.getAttributes().get("userId");
            if (uid instanceof Long l) userId = l;
            else if (uid instanceof Integer i) userId = i.longValue();

            Object wid = session.getAttributes().get("workspaceId");
            if (wid instanceof Long l) workspaceId = l;
            else if (wid instanceof Integer i) workspaceId = i.longValue();
        }

        bridgeManager.onConnect(session, workspaceId, userId);
        log.info("[Bridge WS] Connection established: sessionId={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            BridgeFrame frame = protocol.parse(message.getPayload());
            BridgeSession bridgeSession = getSession(session);

            String error = protocol.validate(frame, bridgeSession);
            if (error != null) {
                log.warn("[Bridge WS] Frame validation failed: {} — {}", error, session.getId());
                sendError(session, error);
                return;
            }

            switch (frame.getType()) {
                case "register" -> {
                    BridgeFrame response = bridgeManager.onRegister(
                            session.getId(), frame.getPayload());
                    session.sendMessage(new TextMessage(response.toJson()));
                }
                case "text" -> {
                    String delta = frame.getPayload() != null
                            ? String.valueOf(frame.getPayload().getOrDefault("delta", ""))
                            : "";
                    bridgeManager.pushToSink(session.getId(),
                            new AgentService.StreamDelta(delta, null));
                }
                case "tool_call" ->
                    bridgeManager.pushToSink(session.getId(),
                            AgentService.StreamDelta.event("tool_call", frame.getPayload()));

                case "tool_result" ->
                    bridgeManager.pushToSink(session.getId(),
                            AgentService.StreamDelta.event("tool_result", frame.getPayload()));

                case "artifact_preview" ->
                    bridgeManager.pushToSink(session.getId(),
                            AgentService.StreamDelta.event("artifact_preview", frame.getPayload()));

                case "progress" ->
                    bridgeManager.pushToSink(session.getId(),
                            AgentService.StreamDelta.event("delegation_progress", frame.getPayload()));

                case "done" -> {
                    Object finalDelta = frame.getPayload() != null
                            ? frame.getPayload().get("delta") : null;
                    if (finalDelta != null && !finalDelta.toString().isEmpty()) {
                        bridgeManager.pushToSink(session.getId(),
                                new AgentService.StreamDelta(finalDelta.toString(), null));
                    }
                    bridgeManager.completeSink(session.getId());
                }

                case "error" ->
                    bridgeManager.errorSink(session.getId(),
                            new RuntimeException("Local agent error: " +
                                    (frame.getPayload() != null
                                            ? frame.getPayload().getOrDefault("message", "unknown")
                                            : "unknown")));

                case "pong" -> bridgeManager.recordPong(session.getId());

                case "status", "status_update" -> {
                    // Agent status update — ignore for now
                }

                default -> log.debug("[Bridge WS] Unhandled frame type: {} from session={}",
                        frame.getType(), session.getId());
            }
        } catch (Exception e) {
            log.error("[Bridge WS] Error handling message from session={}: {}",
                    session.getId(), e.getMessage());
            try {
                sendError(session, "Internal error: " + e.getMessage());
            } catch (Exception ignored) { }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("[Bridge WS] Connection closed: sessionId={} status={}",
                session.getId(), status);
        bridgeManager.onDisconnect(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("[Bridge WS] Transport error for session={}: {}",
                session.getId(), exception.getMessage());
        bridgeManager.onDisconnect(session.getId());
    }

    private BridgeSession getSession(WebSocketSession wsSession) {
        BridgeSession session = bridgeManager.getSession(wsSession.getId());
        if (session == null) {
            throw new IllegalStateException("No bridge session for WS session: " + wsSession.getId());
        }
        return session;
    }

    private void sendError(WebSocketSession session, String message) throws Exception {
        BridgeFrame errorFrame = new BridgeFrame();
        errorFrame.setType("error");
        errorFrame.setSeq(0);
        errorFrame.setTs(System.currentTimeMillis());
        errorFrame.setPayload(Map.of("code", "PROTOCOL_ERROR", "message", message));
        session.sendMessage(new TextMessage(errorFrame.toJson()));
    }
}
