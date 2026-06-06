# Local Agent Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the platform-side local Agent WebSocket bridge — 6 new backend files + 4 modified backend files + 4 modified frontend files.

**Architecture:** BridgedAgent extends BaseAgent, implements StructuredStreamCapable. When a chat arrives, it sends a `chat_request` frame over WebSocket and returns a `Flux.create(sink)` that the WebSocket handler pushes incoming frames into as `StreamDelta` events. The existing ChatController/SSE pipeline consumes these deltas with zero changes.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring WebSocket, Reactor Flux, MyBatis-Plus, Vue 3 + Naive UI

**Spec:** `docs/superpowers/specs/2026-05-24-local-agent-bridge-implementation-design.md`

---

## File Structure

```
Create:
  mateclaw-server/src/main/java/vip/mate/agent/bridge/model/BridgeFrame.java
  mateclaw-server/src/main/java/vip/mate/agent/bridge/model/BridgeSession.java
  mateclaw-server/src/main/java/vip/mate/agent/bridge/AgentBridgeProtocol.java
  mateclaw-server/src/main/java/vip/mate/agent/bridge/AgentBridgeManager.java
  mateclaw-server/src/main/java/vip/mate/agent/bridge/BridgedAgent.java
  mateclaw-server/src/main/java/vip/mate/agent/bridge/AgentBridgeWsHandler.java

Modify:
  mateclaw-server/src/main/java/vip/mate/agent/AgentGraphBuilder.java        (add local_cli branch)
  mateclaw-server/src/main/java/vip/mate/config/WebSocketConfig.java         (register handler)
  mateclaw-server/src/main/java/vip/mate/config/SecurityConfig.java          (permitAll)
  mateclaw-server/src/main/resources/application.yml                         (config block)
  AIagent_frontend/src/views/AgentManageView.vue                             (local_cli tab + indicator)
  AIagent_frontend/src/views/AgentDetailView.vue                             (local_cli type + info)
  AIagent_frontend/src/views/SettingsView.vue                                (bridge usage section)
  AIagent_frontend/src/assets/styles/naive-theme.js                          (status colors)
```

---

### Task 1: BridgeFrame DTO

**Files:**
- Create: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/bridge/model/BridgeFrame.java`

- [ ] **Step 1: Create BridgeFrame.java**

```java
package vip.mate.agent.bridge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * WebSocket 协议帧，{type, seq, ts, payload}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BridgeFrame {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String type;
    private long seq;
    private long ts;
    private Map<String, Object> payload;

    public static BridgeFrame of(String type, Map<String, Object> payload) {
        BridgeFrame frame = new BridgeFrame();
        frame.type = type;
        frame.seq = 0; // set by sender
        frame.ts = System.currentTimeMillis();
        frame.payload = payload;
        return frame;
    }

    public static BridgeFrame parse(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, BridgeFrame.class);
    }

    public String toJson() throws JsonProcessingException {
        return MAPPER.writeValueAsString(this);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/bridge/model/BridgeFrame.java
git commit -m "feat: add BridgeFrame protocol DTO"
```

---

### Task 2: BridgeSession Wrapper

**Files:**
- Create: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/bridge/model/BridgeSession.java`

- [ ] **Step 1: Create BridgeSession.java**

```java
package vip.mate.agent.bridge.model;

import lombok.Data;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.FluxSink;
import vip.mate.agent.AgentService;

import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket 会话包装，绑定 agentId 和响应 sink。
 */
@Data
public class BridgeSession {

    private final String sessionId;
    private final WebSocketSession wsSession;
    private final Long workspaceId;
    private final Long userId;

    /** 注册后绑定 — null 表示未注册 */
    private volatile String agentId;

    /** 注册帧带来的工作目录 */
    private volatile String workDir;

    /** 最后一次 pong 时间 */
    private volatile long lastPongAt = System.currentTimeMillis();

    /** 帧序号校验 */
    private final AtomicLong lastSeq = new AtomicLong(0);

    /** 当前活跃的响应流 — 同一时间最多一个 turn */
    private volatile FluxSink<AgentService.StreamDelta> responseSink;

    public BridgeSession(String sessionId, WebSocketSession wsSession,
                         Long workspaceId, Long userId) {
        this.sessionId = sessionId;
        this.wsSession = wsSession;
        this.workspaceId = workspaceId;
        this.userId = userId;
    }

    public boolean isRegistered() {
        return agentId != null;
    }

    public void recordPong() {
        this.lastPongAt = System.currentTimeMillis();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/bridge/model/BridgeSession.java
git commit -m "feat: add BridgeSession WebSocket wrapper"
```

---

### Task 3: AgentBridgeProtocol

**Files:**
- Create: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/bridge/AgentBridgeProtocol.java`

- [ ] **Step 1: Create AgentBridgeProtocol.java**

```java
package vip.mate.agent.bridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.agent.bridge.model.BridgeFrame;
import vip.mate.agent.bridge.model.BridgeSession;

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
                // Don't reject — could be reordering, just log
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
```

- [ ] **Step 2: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/bridge/AgentBridgeProtocol.java
git commit -m "feat: add AgentBridgeProtocol frame parser/validator"
```

---

### Task 4: AgentBridgeManager

**Files:**
- Create: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/bridge/AgentBridgeManager.java`

- [ ] **Step 1: Read AgentEntity and AgentMapper for reference**

Read `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/model/AgentEntity.java` and `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/repository/AgentMapper.java` to confirm field names and query methods.

- [ ] **Step 2: Create AgentBridgeManager.java**

```java
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

import java.io.IOException;
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
```

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/bridge/AgentBridgeManager.java
git commit -m "feat: add AgentBridgeManager connection registry and routing"
```

---

### Task 5: BridgedAgent

**Files:**
- Create: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/bridge/BridgedAgent.java`

- [ ] **Step 1: Create BridgedAgent.java**

```java
package vip.mate.agent.bridge;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import vip.mate.agent.AgentService;
import vip.mate.agent.AgentState;
import vip.mate.agent.BaseAgent;
import vip.mate.agent.StructuredStreamCapable;
import vip.mate.agent.bridge.model.BridgeFrame;
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

    /** AgentGraphBuilder 通过 setter 注入 */
    public BridgedAgent(ConversationService conversationService, AgentBridgeManager bridgeManager) {
        super(null, conversationService);
        this.bridgeManager = bridgeManager;
    }

    @Override
    public Flux<AgentService.StreamDelta> chatStructuredStream(String userMessage, String conversationId) {
        if (!bridgeManager.isOnline(agentId)) {
            return Flux.error(new IllegalStateException("Local agent '" + agentName + "' is offline"));
        }

        BridgeFrame request = BridgeFrame.of("chat_request", Map.of(
                "message", userMessage,
                "conversationId", conversationId,
                "systemPrompt", systemPrompt != null ? systemPrompt : ""
        ));

        return Flux.<AgentService.StreamDelta>create(sink -> {
            setState(AgentState.RUNNING);
            bridgeManager.registerResponseSink(agentId, sink);

            sink.onCancel(() -> {
                // User clicked stop — notify the local agent
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
                        log.error("[BridgedAgent] Failed to send chat_request to agent={}: {}",
                                agentId, ex.getMessage());
                        sink.error(ex);
                        return null;
                    });
        }, FluxSink.OverflowStrategy.LATEST);
    }

    @Override
    public String chat(String userMessage, String conversationId) {
        // Synchronous fallback: collect from Flux
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
```

- [ ] **Step 2: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/bridge/BridgedAgent.java
git commit -m "feat: add BridgedAgent extending BaseAgent with WebSocket relay"
```

---

### Task 6: AgentBridgeWsHandler

**Files:**
- Create: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/bridge/AgentBridgeWsHandler.java`

- [ ] **Step 1: Create AgentBridgeWsHandler.java**

```java
package vip.mate.agent.bridge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import vip.mate.agent.AgentService;
import vip.mate.agent.bridge.model.BridgeFrame;
import vip.mate.agent.bridge.model.BridgeSession;

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
        // Extract user/workspace from handshake. Spring Security sets the principal
        // on the HTTP upgrade request, which WebSocketSession exposes via getPrincipal().
        Long workspaceId = 1L; // default; override from handshake attribute if available
        Long userId = 0L;

        Principal principal = session.getPrincipal();
        if (principal != null) {
            // principal.getName() is username; we need userId.
            // userId is stored in handshake attributes by JwtAuthFilter
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
                    // Emit final text if any, then complete
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
```

- [ ] **Step 2: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/bridge/AgentBridgeWsHandler.java
git commit -m "feat: add AgentBridgeWsHandler WebSocket endpoint"
```

---

### Task 7: AgentGraphBuilder — local_cli branch

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/AgentGraphBuilder.java`

- [ ] **Step 1: Add BridgedAgent build method and branch**

Add this method after the existing agent build methods (e.g., before `buildReActAgent`):

```java
    // filepath: mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/AgentGraphBuilder.java
    // Add import at top of file:
    // import vip.mate.agent.bridge.BridgedAgent;
    // import vip.mate.agent.bridge.AgentBridgeManager;

    private final AgentBridgeManager agentBridgeManager;

    /**
     * Build a BridgedAgent for local_cli type — no StateGraph needed;
     * chat is relayed over WebSocket to the local CLI process.
     */
    BridgedAgent buildBridgedAgent(AgentEntity entity) {
        BridgedAgent agent = new BridgedAgent(conversationService, agentBridgeManager);
        return agent;
    }
```

Then in the `build(AgentEntity entity, String modelProvider, String modelName)` method, add a new branch before the `supportsStateGraph` check:

```java
        // filepath: mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/AgentGraphBuilder.java
        // Replace the if-else block starting at approximately line 284 with:

        BaseAgent agent;
        boolean toolCallingEnabled;
        if ("local_cli".equals(entity.getAgentType())) {
            // Local CLI agent — no LLM model needed, bridged over WebSocket
            agent = buildBridgedAgent(entity);
            toolCallingEnabled = true;  // local CLI handles tools itself
            log.info("Built BridgedAgent: {} (local_cli)", entity.getName());
        } else if ("plan_execute".equals(entity.getAgentType())) {
            agent = buildPlanExecuteAgent(toolSet, runtimeModel, maxIter, entity.getId());
            toolCallingEnabled = true;
            log.info("Built StateGraph Plan-Execute agent: {} (maxIterations={}, tools={}, protocol={})",
                    entity.getName(), maxIter, toolSet.size(), protocol.getId());
        } else {
            agent = buildReActAgent(toolSet, runtimeModel, maxIter, entity.getId());
            toolCallingEnabled = true;
            log.info("Built StateGraph ReAct agent: {} (maxIterations={}, tools={}, protocol={})",
                    entity.getName(), maxIter, toolSet.size(), protocol.getId());
        }
```

Then wrap the protocol check to skip for local_cli:

```java
        // Move the supportsStateGraph check inside the else branches,
        // only for non-local_cli agents. The protocol check at ~line 277 becomes:

        if (!"local_cli".equals(entity.getAgentType())) {
            if (!supportsStateGraph(protocol)) {
                throw new MateClawException("err.agent.protocol_not_supported",
                        "当前不支持协议 " + protocol.getId()
                        + "，请切换到 DashScope 或 OpenAI-compatible 模型");
            }
        }
```

And wrap the common property setting to skip model-related properties for local_cli:

```java
        // After the agent creation block, wrap model-dependent properties:

        agent.agentId = String.valueOf(entity.getId());
        agent.agentName = entity.getName();
        agent.systemPrompt = enhancedPrompt;
        agent.maxIterations = maxIter;

        if (!"local_cli".equals(entity.getAgentType())) {
            agent.modelName = runtimeModel.getModelName();
            agent.modelCapabilities = modelCapabilityService.resolve(
                    runtimeModel.getModelName(), runtimeModel.getModalities());
            agent.runtimeProviderId = provider != null ? provider.getProviderId() : "";
            agent.runtimeModelConfig = runtimeModel;
            agent.temperature = runtimeModel.getTemperature();
            agent.maxTokens = runtimeModel.getMaxTokens();
            agent.maxInputTokens = runtimeModel.getMaxInputTokens();
            agent.topP = runtimeModel.getTopP();
        }

        agent.toolSet = toolSet;
        agent.multimodalRouter = multimodalRouter;
        agent.mediaCaptionService = mediaCaptionService;
        agent.userLocale = resolveLocale();
        agent.toolCallingEnabled = toolCallingEnabled;
```

- [ ] **Step 2: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/AgentGraphBuilder.java
git commit -m "feat: add local_cli agent type branch in AgentGraphBuilder"
```

---

### Task 8: WebSocketConfig and SecurityConfig

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/config/WebSocketConfig.java`
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/config/SecurityConfig.java`

- [ ] **Step 1: Register handler in WebSocketConfig**

In `WebSocketConfig.java`, inject the new handler and register it:

```java
    // filepath: mateclaw-dev/mateclaw-server/src/main/java/vip/mate/config/WebSocketConfig.java
    // Add import:
    // import vip.mate.agent.bridge.AgentBridgeWsHandler;

    private final AgentBridgeWsHandler agentBridgeHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(talkModeHandler, "/api/v1/talk/ws")
                .setAllowedOrigins("*");
        registry.addHandler(agentBridgeHandler, "/api/v1/agent-bridge/ws")
                .setAllowedOrigins("*");
    }
```

- [ ] **Step 2: Add permitAll in SecurityConfig**

In `SecurityConfig.java`, add the WebSocket endpoint path:

```java
    // filepath: mateclaw-dev/mateclaw-server/src/main/java/vip/mate/config/SecurityConfig.java
    // Add to the permitAll list:
                    "/api/v1/agent-bridge/ws",
```

- [ ] **Step 3: Add bridge config to application.yml**

In `mateclaw-dev/mateclaw-server/src/main/resources/application.yml`, add:

```yaml
mateclaw:
  bridge:
    heartbeat-interval: 30s
    heartbeat-timeout: 60s
    max-connections-per-workspace: 20
    auto-register: true
    frame-max-size: 65536
```

- [ ] **Step 4: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/config/WebSocketConfig.java
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/config/SecurityConfig.java
git add mateclaw-dev/mateclaw-server/src/main/resources/application.yml
git commit -m "feat: register agent-bridge WebSocket endpoint and config"
```

---

### Task 9: Frontend — AgentManageView

**Files:**
- Modify: `AIagent_frontend/src/views/AgentManageView.vue`

- [ ] **Step 1: Add local_cli filter tab**

In the `typeTabs` array (around line 12), add:

```javascript
{ label: '本地CLI', value: 'local_cli' },
```

- [ ] **Step 2: Add online indicator and icon**

In the template, for each agent card, add a local_cli indicator. Find the card rendering loop and add:

```html
<!-- Before the agent name display, add icon prefix -->
<NIcon v-if="agent.agentType === 'local_cli'" size="16" style="margin-right:4px;vertical-align:middle">
  <svg><!-- monitor icon --></svg>
</NIcon>

<!-- Add connection status indicator for local_cli agents -->
<NTag v-if="agent.agentType === 'local_cli'"
      :type="agent.agentStatus === 'AVAILABLE' ? 'success' : 'default'"
      size="tiny" round>
  {{ agent.agentStatus === 'AVAILABLE' ? '在线' : '离线' }}
</NTag>
```

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/views/AgentManageView.vue
git commit -m "feat: add local_cli filter and online status to AgentManageView"
```

---

### Task 10: Frontend — AgentDetailView

**Files:**
- Modify: `AIagent_frontend/src/views/AgentDetailView.vue`

- [ ] **Step 1: Add local_cli to agentType options**

In the agent type selector (around line 54), add:

```javascript
{ label: '本地 CLI (local_cli)', value: 'local_cli' },
```

- [ ] **Step 2: Add connection info for local_cli agents**

After the basic config section, add a conditional connection info block:

```html
<NCard v-if="form.agentType === 'local_cli'" title="连接信息" size="small" style="margin-top:12px">
  <NSpace vertical>
    <div>
      <NText depth="3">连接状态</NText>
      <NTag :type="form.agentStatus === 'AVAILABLE' ? 'success' : 'default'" size="small">
        {{ form.agentStatus === 'AVAILABLE' ? '在线' : '离线' }}
      </NTag>
    </div>
    <NText depth="3">
      本地 CLI Agent 通过 WebSocket 连接到平台，模型在本地运行。
      请在「设置 → 本地 Agent 接入」中获取接入 Token 和使用说明。
    </NText>
  </NSpace>
</NCard>
```

- [ ] **Step 3: Hide model selection for local_cli**

Wrap the model name input with a `v-if`:

```html
<NFormItem v-if="form.agentType !== 'local_cli'" label="模型" path="modelName">
  <NInput v-model:value="form.modelName" placeholder="留空则使用全局默认模型" />
</NFormItem>
```

- [ ] **Step 4: Commit**

```bash
git add AIagent_frontend/src/views/AgentDetailView.vue
git commit -m "feat: add local_cli type and connection info to AgentDetailView"
```

---

### Task 11: Frontend — SettingsView and naive-theme

**Files:**
- Modify: `AIagent_frontend/src/views/SettingsView.vue`
- Modify: `AIagent_frontend/src/assets/styles/naive-theme.js`

- [ ] **Step 1: Add bridge usage section to SettingsView**

After the PAT tokens section, add:

```html
<NCard title="本地 Agent 接入" size="small" style="margin-top:16px">
  <template #header-extra>
    <NTag type="info" size="small">BETA</NTag>
  </template>
  <NSpace vertical>
    <NText>
      使用本地 CLI Agent（Claude Code、OpenCode 等）接入平台：
    </NText>
    <NCard size="small" embedded>
      <NCode code="claude --bridge wss://your-host/api/v1/agent-bridge/ws?token=mc_YOUR_PAT_TOKEN \
       --name &quot;我的Claude&quot; \
       --work-dir /home/dev/project" language="bash" />
    </NCard>
    <NText depth="3">
      1. 先从上方「个人访问令牌」生成一个 PAT<br/>
      2. 在本地终端执行上述命令<br/>
      3. 连接成功后，Agent 将出现在 Agent 管理列表中
    </NText>
  </NSpace>
</NCard>
```

- [ ] **Step 2: Add status colors to naive-theme.js**

In the theme overrides, add:

```javascript
// Add connection status colors
connectionOnline: '#10b981',
connectionOffline: '#9ca3af',
```

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/views/SettingsView.vue
git add AIagent_frontend/src/assets/styles/naive-theme.js
git commit -m "feat: add local agent bridge usage section and status colors"
```

---

### Task 12: Integration Verification

- [ ] **Step 1: Compile backend**

```bash
cd mateclaw-dev && mvnw clean compile -pl mateclaw-server -am
```
Expected: BUILD SUCCESS

- [ ] **Step 2: Build frontend**

```bash
cd AIagent_frontend && npm run build
```
Expected: No errors, dist/ generated

- [ ] **Step 3: Review checklist**

- [ ] BridgedAgent extends BaseAgent and implements StructuredStreamCapable
- [ ] AgentBridgeManager uses ConcurrentHashMap for thread safety
- [ ] WebSocket endpoint registered at /api/v1/agent-bridge/ws
- [ ] SecurityConfig permits public access to the WS endpoint (auth via ?token= query param)
- [ ] AgentGraphBuilder creates BridgedAgent for agentType=local_cli without requiring a ChatModel
- [ ] Frontend shows local_cli tab, online/offline indicator, and usage instructions
- [ ] PAT system reused without modifications
- [ ] ChatController, AgentService, DelegateAgentTool unchanged
