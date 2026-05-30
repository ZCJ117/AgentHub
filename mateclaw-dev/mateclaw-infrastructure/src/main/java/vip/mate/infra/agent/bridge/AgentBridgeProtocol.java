package vip.mate.infra.agent.bridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.infra.agent.bridge.model.BridgeFrame;
import vip.mate.infra.agent.bridge.model.BridgeSession;

import java.util.Set;

/**
 * 协议帧编解码 + 校验。
 */
@Slf4j
@Component
public class AgentBridgeProtocol {

    private static final int MAX_FRAME_BYTES = 65536;

    private static final Set<String> KNOWN_TYPES = Set.of(
            "register", "registered", "chat_request", "delegation_request",
            "stop_request", "context_update",
            "text", "tool_call", "tool_result", "artifact_preview",
            "progress", "done", "error",
            "ping", "pong", "status", "status_update"
    );

    public BridgeFrame parse(String payload) throws JsonProcessingException {
        if (payload == null || payload.length() > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Frame too large: " +
                    (payload == null ? 0 : payload.length()) + " bytes (max " + MAX_FRAME_BYTES + ")");
        }
        return BridgeFrame.parse(payload);
    }

    public String encode(BridgeFrame frame) throws JsonProcessingException {
        return frame.toJson();
    }

    /**
     * Validate and update session state. Returns null if valid, error message if invalid.
     */
    public String validate(BridgeFrame frame, BridgeSession session) {
        if (frame.getType() == null || !KNOWN_TYPES.contains(frame.getType())) {
            return "Unknown frame type: " + frame.getType();
        }

        // Seq monotonic check (skip for pong which is lightweight)
        if (!"pong".equals(frame.getType()) && !"ping".equals(frame.getType())) {
            long seq = frame.getSeq();
            long last = session.getLastSeq().get();
            if (seq <= last && last > 0) {
                log.warn("[Bridge] Non-monotonic seq for session {}: {} <= {}",
                        session.getSessionId(), seq, last);
            }
            session.getLastSeq().set(Math.max(seq, last));
        }

        // Non-register frames require agentId bound
        if (!"register".equals(frame.getType()) && !session.isRegistered()) {
            return "Session not registered — send register frame first";
        }

        // Register frame requires agentName
        if ("register".equals(frame.getType())) {
            Object name = frame.getPayload() != null ? frame.getPayload().get("agentName") : null;
            if (name == null || name.toString().isBlank() || name.toString().length() > 100) {
                return "register frame requires agentName (1-100 chars)";
            }
        }

        return null; // valid
    }
}
