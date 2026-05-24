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
