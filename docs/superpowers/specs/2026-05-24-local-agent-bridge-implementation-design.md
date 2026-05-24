# Local Agent Bridge — Implementation Design Spec

**版本**: v1.0
**日期**: 2026-05-24
**状态**: 设计已确认，待实现
**基于**: local-agent-bridge-design.md (架构方案)

## 1. 范围

**Full platform side only**: 全部后端 Java 代码 + 前端改动。本地 npm CLI 客户端不在本次范围。

### 包含
- 后端: AgentBridgeManager, BridgedAgent, AgentBridgeWsHandler, AgentBridgeProtocol, 协议帧 DTO
- 后端: AgentGraphBuilder 扩展 (local_cli 分支)
- 后端: WebSocketConfig / SecurityConfig 注册
- 后端: 配置文件 (mateclaw.bridge.*)
- 前端: AgentManageView, AgentDetailView, SettingsView, naive-theme 改动

### 不包含
- 本地 npm CLI 客户端 (agent-bridge-client)
- Claude Code / OpenCode 适配器
- 断线自动重连客户端逻辑

## 2. 核心设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| BridgedAgent 继承方式 | 直接 extends BaseAgent, implements StructuredStreamCapable | 本地 CLI 已有自己的 tool guard 和 memory，平台侧无需重复 |
| PAT 机制 | 复用已有 PersonalAccessTokenService | 已有完整 CRUD + mc_ 前缀 JWT 分发，零新增 |
| Agent 注册 | Pre-create (UI) + Auto-register (WebSocket) | 用户可在 UI 中预配置 systemPrompt/icon，也可让 CLI 首次连接自动创建 |
| WebSocket 认证 | URL query param `?token=mc_xxx` | 复用 JwtAuthFilter 已有的 query param PAT 认证路径 |
| Flux 桥接模式 | Flux.create(sink) 按 agentId 注册 | 每个 agent 同一时间只有一个活跃 turn |

## 3. 新增文件

```
mateclaw-server/src/main/java/vip/mate/agent/bridge/
├── AgentBridgeManager.java         # 连接注册表 + FluxSink 管理 + 心跳 + 路由
├── BridgedAgent.java               # 继承 BaseAgent，实现 StructuredStreamCapable
├── AgentBridgeWsHandler.java       # Spring TextWebSocketHandler
├── AgentBridgeProtocol.java        # 帧解析 + 校验 (seq, size, required fields)
└── model/
    ├── BridgeFrame.java            # 协议帧 DTO: {type, seq, ts, payload}
    └── BridgeSession.java          # WS 会话包装: session + agentId + workspaceId + sink
```

## 4. 修改文件

### 后端

| 文件 | 改动 | 行数 |
|------|------|------|
| AgentGraphBuilder.java | 新增 `agentType=local_cli` 分支 → `buildBridgedAgent()` | ~15 |
| WebSocketConfig.java | 注册 `/api/v1/agent-bridge/ws` → AgentBridgeWsHandler | ~5 |
| SecurityConfig.java | permitAll 添加 `/api/v1/agent-bridge/ws` | +1 |
| application.yml | 新增 `mateclaw.bridge.*` 配置块 | ~6 |

### 前端

| 文件 | 改动 | 行数 |
|------|------|------|
| AgentManageView.vue | typeTabs 增加 local_cli，卡片增加在线状态和工作目录 | ~12 |
| AgentDetailView.vue | agentType 下拉增加 local_cli，显示连接信息卡片 | ~15 |
| SettingsView.vue | 新增"本地 Agent 接入"使用说明区块 | ~20 |
| naive-theme.js | 新增在线/离线状态色 | ~3 |

## 5. BridgedAgent 设计

```java
public class BridgedAgent extends BaseAgent implements StructuredStreamCapable {
    private AgentBridgeManager bridgeManager;

    @Override
    public Flux<AgentService.StreamDelta> chatStructuredStream(
            String userMessage, String conversationId) {

        BridgeFrame request = BridgeFrame.of("chat_request", Map.of(
            "message", userMessage,
            "conversationId", conversationId,
            "systemPrompt", this.systemPrompt
        ));

        return Flux.<AgentService.StreamDelta>create(sink -> {
            bridgeManager.registerResponseSink(agentId, sink);
            bridgeManager.send(agentId, request)
                .exceptionally(ex -> { sink.error(ex); return null; });
            sink.onCancel(() -> bridgeManager.send(agentId,
                BridgeFrame.of("stop_request", Map.of())));
            sink.onDispose(() -> bridgeManager.unregisterResponseSink(agentId));
        });
    }
}
```

### Frame → StreamDelta 映射

| WS Frame type | StreamDelta 输出 | 前端效果 |
|---------------|-----------------|---------|
| text | `StreamDelta(content=payload.delta)` | 追加到消息气泡 |
| tool_call | `StreamDelta.event("tool_call", payload)` | 显示工具调用指示 |
| tool_result | `StreamDelta.event("tool_result", payload)` | 显示工具结果 |
| artifact_preview | `StreamDelta.event("artifact_preview", payload)` | 产物预览卡片 |
| progress | `StreamDelta.event("delegation_progress", payload)` | 调度进度 |
| done | `sink.complete()` | 标记完成，显示 token 用量 |
| error | `sink.error(...)` | 显示错误，启用重试 |

## 6. AgentBridgeManager 设计

```java
public class AgentBridgeManager {
    // agentId → BridgeSession
    private final ConcurrentHashMap<String, BridgeSession> sessions;
    // agentId → active FluxSink (only one turn at a time)
    private final ConcurrentHashMap<String, FluxSink<StreamDelta>> responseSinks;
    // Scheduled heartbeat watchdog

    BridgeSession onConnect(WebSocketSession wsSession, Authentication auth);
    void onRegister(String sessionId, BridgeAgentRegistration reg);
    CompletableFuture<Void> send(String agentId, BridgeFrame frame);
    void registerResponseSink(String agentId, FluxSink<StreamDelta> sink);
    void pushToSink(String sessionId, BridgeFrame frame);
    void completeSink(String sessionId, BridgeFrame doneFrame);
    void errorSink(String sessionId, BridgeFrame errorFrame);
    void onDisconnect(String sessionId);
    boolean isOnline(String agentId);
}
```

### 边界情况
- Agent 离线时发起 chat → sink.error(OfflineException)
- 同一 agent 并发两个 chat → 拒绝第二个 (BusyException)
- WS 中断 mid-stream → sink.error(DisconnectException)
- 跨 workspace 重名 register → 拒绝

## 7. Agent 注册流程

```
CLI 连接 → WebSocket 握手 (?token=mc_xxx)
  → JwtAuthFilter 解析 PAT → SecurityContext (userId, workspaceId)
  → AgentBridgeWsHandler.afterConnectionEstablished()
  → BridgeSession 创建 (agentId=null)

CLI 发送 register 帧 {agentName, capabilityTags, workDir}
  → AgentBridgeManager.onRegister():
    1. 查询 mate_agent WHERE name=X AND workspace_id=Y
    2. 存在且 creatorUserId 匹配 → 复用 agentId (重连)
    3. 存在但 creatorUserId 不匹配 → 拒绝
    4. 不存在 → 自动创建 AgentEntity (agentType=local_cli, enabled=true)
    5. 绑定 agentId → BridgeSession, 更新 status=AVAILABLE

平台回复 registered 帧 {agentId}
CLI 发送 status 帧 → 更新 AgentEntity.agentStatus = AVAILABLE
```

## 8. AgentBridgeWsHandler 设计

```java
public class AgentBridgeWsHandler extends TextWebSocketHandler {
    // 每个 WS session 由独立线程处理，BridgeManager 负责并发安全

    afterConnectionEstablished(session) → bridgeManager.onConnect(session, auth)

    handleTextMessage(session, message):
      frame = protocol.parse(message.getPayload())
      protocol.validate(frame)
      switch(frame.type):
        "register"      → register(session, frame)
        "text"          → pushToSink → StreamDelta
        "tool_call"     → pushToSink → StreamDelta
        "tool_result"   → pushToSink → StreamDelta
        "artifact_preview" → pushToSink → StreamDelta
        "progress"      → pushToSink → StreamDelta
        "done"          → completeSink
        "error"         → errorSink
        "pong"          → recordPong

    afterConnectionClosed(session, status) → bridgeManager.onDisconnect()
}
```

## 9. 协议校验

| 校验 | 规则 | 失败处理 |
|------|------|---------|
| 帧大小 | ≤ 64KB | 关闭连接 |
| Seq 单调性 | seq > lastSeq (per session) | 丢弃帧，日志 warning |
| Type 已知 | type ∈ 已知类型集合 | 丢弃帧，日志 warning |
| Register 必填 | agentName 必填，≤100 字符 | 返回 error 帧，保持连接 |
| Auth guard | 非 register 帧需 agentId 已绑定 | 丢弃帧，返回 error 帧 |

## 10. 错误处理

| 场景 | 平台响应 | 用户可见 |
|------|---------|---------|
| Agent 离线 | Flux.error(OfflineException) | "Agent is offline" 错误气泡 |
| WS 中断 mid-stream | sink.error(DisconnectException) | 部分内容 + "Connection lost" + 重试按钮 |
| Register 重名 | error 帧，保持 WS 连接 | CLI 显示错误，改名重试 |
| Token 无效 | Spring Security 拒绝 HTTP upgrade → 401 | CLI: "Authentication failed" |
| 协议违规 | 丢弃帧 / 关闭连接 | 帧丢弃；严重则断开 |
| 用户点 Stop | Flux cancel → stop_request 帧 | 部分内容保留，标记 stopped |
| 心跳超时 (60s) | 关闭 WS，设置 OFFLINE，complete sink | Agent 离线指示；进行中的 chat 报错 |

## 11. 配置

```yaml
mateclaw:
  bridge:
    heartbeat-interval: 30s
    heartbeat-timeout: 60s
    max-connections-per-workspace: 20
    auto-register: true
    frame-max-size: 65536
```

## 12. 前端改动详情

### AgentManageView.vue
- typeTabs 数组增加 `{ label: '本地CLI', value: 'local_cli' }`
- Agent 卡片: local_cli 类型显示 🖥 前缀图标
- 状态点: AVAILABLE→绿色, OFFLINE→灰色 (复用已有 statusColor)
- Tooltip 显示 workDir

### AgentDetailView.vue
- agentType 选项增加 `{ label: '本地 CLI (local_cli)', value: 'local_cli' }`
- local_cli 类型时: 显示连接状态卡片 (在线/离线/最后连接时间)，隐藏模型选择 (本地运行)

### SettingsView.vue
- 新增 "本地 Agent 接入" 区块 (复用已有 PAT UI)
- CLI 使用示例代码块: `claude --bridge wss://host/ws/agent-bridge --token mc_xxx --name "MyAgent"`

### naive-theme.js
- 在线状态色: `successColor: '#10b981'` (已有)
- 离线状态色: 新增灰色变量

## 13. 不影响的部分

- ChatController、AgentService、OrchestratorService: 零改动
- BaseAgent、ReActAgent、PlanExecuteAgent: 零改动
- DelegateAgentTool: 零改动 (通过 AgentService.chat() 透明调用 BridgedAgent)
- 前端 ChatStore、ChatView、MessageBubble、PlanCard: 零改动
- 数据库迁移: 不需要新迁移 (AgentEntity 已有 agentType 字段，V120 已有 agentStatus)
- PAT 系统: 零改动 (完全复用)
