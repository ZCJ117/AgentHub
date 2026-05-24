package vip.mate.agent.bridge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.FluxSink;
import vip.mate.agent.AgentService;
import vip.mate.agent.bridge.model.BridgeFrame;
import vip.mate.agent.bridge.model.BridgeSession;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 连接注册表 + FluxSink 管理 + 心跳。
 *
 * <p>Thread-safe: all state in ConcurrentHashMap with volatile fields on BridgeSession.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentBridgeManager {

    private final AgentMapper agentMapper;
    private final AgentBridgeProtocol protocol;

    /** sessionId → BridgeSession */
    private final ConcurrentHashMap<String, BridgeSession> sessions = new ConcurrentHashMap<>();

    /** agentId → sessionId (快速路由) */
    private final ConcurrentHashMap<String, String> agentToSession = new ConcurrentHashMap<>();

    private static final long HEARTBEAT_INTERVAL_MS = 30_000;
    private static final long HEARTBEAT_TIMEOUT_MS = 60_000;

    // ── Connection lifecycle ──────────────────────────────────────────

    public BridgeSession onConnect(WebSocketSession wsSession, Long workspaceId, Long userId) {
        String sessionId = wsSession.getId();
        BridgeSession session = new BridgeSession(sessionId, wsSession, workspaceId, userId);
        sessions.put(sessionId, session);
        log.info("[Bridge] Connected: sessionId={} workspaceId={} userId={}",
                sessionId, workspaceId, userId);
        return session;
    }

    /**
     * 绑定 agentId 到已连接的会话（register 帧到达时调用）。
     */
    public BridgeFrame onRegister(String sessionId, Map<String, Object> payload) {
        BridgeSession session = sessions.get(sessionId);
        if (session == null) {
            return errorFrame("session not found");
        }

        String agentName = (String) payload.get("agentName");
        Object workDir = payload.get("workDir");
        Object capabilityTags = payload.get("capabilityTags");

        // Query existing agent by name within workspace
        AgentEntity entity = agentMapper.selectOne(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getName, agentName)
                .eq(AgentEntity::getWorkspaceId, session.getWorkspaceId())
                .eq(AgentEntity::getDeleted, 0)
                .last("LIMIT 1"));

        if (entity != null) {
            // Existing agent — verify ownership
            if (!session.getUserId().equals(entity.getCreatorUserId())) {
                return errorFrame("Agent name '" + agentName +
                        "' is already registered by another user in this workspace");
            }
            // Update status and possibly capability tags
            entity.setAgentStatus("AVAILABLE");
            if (capabilityTags != null && entity.getCapabilityTags() == null) {
                entity.setCapabilityTags(capabilityTags.toString());
            }
            agentMapper.updateById(entity);
            session.setAgentId(String.valueOf(entity.getId()));
        } else {
            // Auto-create new agent entity
            AgentEntity newEntity = new AgentEntity();
            newEntity.setName(agentName);
            newEntity.setAgentType("local_cli");
            newEntity.setAgentStatus("AVAILABLE");
            newEntity.setEnabled(true);
            newEntity.setIsPublic(1);
            newEntity.setWorkspaceId(session.getWorkspaceId());
            newEntity.setCreatorUserId(session.getUserId());
            newEntity.setDescription("Local CLI agent: " + agentName);
            newEntity.setCapabilityTags(capabilityTags != null ? capabilityTags.toString() : "[]");
            newEntity.setDeleted(0);
            agentMapper.insert(newEntity);
            session.setAgentId(String.valueOf(newEntity.getId()));
            entity = newEntity;
        }

        if (workDir != null) {
            session.setWorkDir(workDir.toString());
        }

        // Bind route
        agentToSession.put(session.getAgentId(), sessionId);
        session.setLastPongAt(System.currentTimeMillis());

        log.info("[Bridge] Registered: agentId={} name={} workDir={}",
                session.getAgentId(), agentName, session.getWorkDir());

        BridgeFrame response = new BridgeFrame();
        response.setType("registered");
        response.setSeq(0);
        response.setTs(System.currentTimeMillis());
        response.setPayload(Map.of("agentId", session.getAgentId()));
        return response;
    }

    // ── Routing ───────────────────────────────────────────────────────

    public boolean isOnline(String agentId) {
        String sessionId = agentToSession.get(agentId);
        return sessionId != null && sessions.containsKey(sessionId);
    }

    /**
     * Send a frame to the given agent's WebSocket, returning a future that completes
     * when the send is done.
     */
    public CompletableFuture<Void> send(String agentId, BridgeFrame frame) {
        String sessionId = agentToSession.get(agentId);
        if (sessionId == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Agent " + agentId + " is offline"));
        }
        BridgeSession session = sessions.get(sessionId);
        if (session == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Agent " + agentId + " session not found"));
        }
        try {
            String json = protocol.encode(frame);
            TextMessage msg = new TextMessage(json);
            CompletableFuture<Void> future = new CompletableFuture<>();
            session.getWsSession().sendMessage(msg)
                    .addCallback(
                            unused -> future.complete(null),
                            future::completeExceptionally);
            return future;
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ── Response sink management ──────────────────────────────────────

    public void registerResponseSink(String agentId, FluxSink<AgentService.StreamDelta> sink) {
        String sessionId = agentToSession.get(agentId);
        if (sessionId == null) {
            sink.error(new IllegalStateException("Agent " + agentId + " is offline"));
            return;
        }
        BridgeSession session = sessions.get(sessionId);
        if (session == null) {
            sink.error(new IllegalStateException("Agent " + agentId + " session not found"));
            return;
        }
        if (session.getResponseSink() != null) {
            sink.error(new IllegalStateException("Agent " + agentId + " already has an active turn"));
            return;
        }
        session.setResponseSink(sink);
    }

    public void unregisterResponseSink(String agentId) {
        String sessionId = agentToSession.get(agentId);
        if (sessionId != null) {
            BridgeSession session = sessions.get(sessionId);
            if (session != null) {
                session.setResponseSink(null);
            }
        }
    }

    public void pushToSink(String sessionId, AgentService.StreamDelta delta) {
        BridgeSession session = sessions.get(sessionId);
        if (session != null && session.getResponseSink() != null) {
            session.getResponseSink().next(delta);
        }
    }

    public void completeSink(String sessionId) {
        BridgeSession session = sessions.get(sessionId);
        if (session != null && session.getResponseSink() != null) {
            session.getResponseSink().complete();
            session.setResponseSink(null);
        }
    }

    public void errorSink(String sessionId, Throwable error) {
        BridgeSession session = sessions.get(sessionId);
        if (session != null && session.getResponseSink() != null) {
            session.getResponseSink().error(error);
            session.setResponseSink(null);
        }
    }

    // ── Disconnect ────────────────────────────────────────────────────

    public void onDisconnect(String sessionId) {
        BridgeSession session = sessions.remove(sessionId);
        if (session == null) return;
        if (session.getAgentId() != null) {
            agentToSession.remove(session.getAgentId());
            // Update agent status to OFFLINE
            try {
                AgentEntity entity = agentMapper.selectById(Long.valueOf(session.getAgentId()));
                if (entity != null) {
                    entity.setAgentStatus("OFFLINE");
                    agentMapper.updateById(entity);
                }
            } catch (Exception e) {
                log.warn("[Bridge] Failed to update agent status on disconnect: {}", e.getMessage());
            }
        }
        // Complete any active sink
        if (session.getResponseSink() != null) {
            session.getResponseSink().error(
                    new IllegalStateException("Agent disconnected"));
            session.setResponseSink(null);
        }
        log.info("[Bridge] Disconnected: sessionId={} agentId={}", sessionId, session.getAgentId());
    }

    public void recordPong(String sessionId) {
        BridgeSession session = sessions.get(sessionId);
        if (session != null) {
            session.recordPong();
        }
    }

    // ── Heartbeat watchdog ────────────────────────────────────────────

    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS)
    public void heartbeatCheck() {
        long now = System.currentTimeMillis();
        for (BridgeSession session : sessions.values()) {
            // Send ping
            if (session.isRegistered()) {
                try {
                    BridgeFrame ping = new BridgeFrame();
                    ping.setType("ping");
                    ping.setSeq(0);
                    ping.setTs(now);
                    ping.setPayload(Map.of());
                    session.getWsSession().sendMessage(new TextMessage(ping.toJson()));
                } catch (Exception e) {
                    log.debug("[Bridge] Ping failed for session {}: {}", session.getSessionId(), e.getMessage());
                }
            }
            // Check pong timeout
            if (now - session.getLastPongAt() > HEARTBEAT_TIMEOUT_MS) {
                log.warn("[Bridge] Heartbeat timeout for session {} agent={}",
                        session.getSessionId(), session.getAgentId());
                try {
                    session.getWsSession().close();
                } catch (Exception e) {
                    // ignore
                }
                onDisconnect(session.getSessionId());
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────

    /** Used by the WS handler for protocol validation. */
    public BridgeSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    private BridgeFrame errorFrame(String message) {
        BridgeFrame frame = new BridgeFrame();
        frame.setType("error");
        frame.setSeq(0);
        frame.setTs(System.currentTimeMillis());
        frame.setPayload(Map.of("code", "REGISTRATION_FAILED", "message", message));
        return frame;
    }
}
