# 本地 Agent 接入方案 — WebSocket Agent Bridge

**版本**: v1.0
**日期**: 2026-05-24
**状态**: 设计完成

## 1. 目标

将本地 CLI Agent 产品（Claude Code、OpenCode 等）作为一等公民接入 AgentHub 多 Agent 协同工作平台，支持单聊和群聊，用户体验与平台托管 Agent 完全一致。

### 1.1 核心需求

- 本地 CLI 进程主动通过 WebSocket 连接到平台
- 单聊中用户直接与本地 Agent 对话，流式查看工具调用和结果
- 群聊中 Orchestrator 可调度本地 Agent 执行子任务
- 前端对本地 Agent 基本无感知，复用现有全部组件

## 2. 整体架构

```
用户浏览器                      AgentHub 平台                    开发者本地机器
─────────                      ────────────                    ──────────────

ChatView ──SSE──→ ChatController ──→ AgentService              Claude Code CLI
(Vue 3)                                      │                 (claude --bridge)
                                              ├─ ReActAgent          │
                                              ├─ PlanExecuteAgent    │
                                              └─ BridgedAgent ◄──WebSocket── AgentBridgeClient
                                                   (新增)                        │
                                              │                            CLI Adapter
OrchestratorService ──→ DelegateAgentTool ────┘
```

### 2.1 核心设计原则

| 原则 | 说明 |
|------|------|
| BridgedAgent 继承 BaseAgent | 本地 Agent 在平台眼中就是一个普通 Agent |
| 协议镜像 SSE 事件流 | WebSocket 消息格式与 SSE 事件类型一一对应 |
| 连接即注册 | 本地 Agent 建立 WebSocket 后自动完成平台注册 |
| CLI 适配器模式 | 桥接客户端通过适配器接口解耦具体 CLI 实现 |

### 2.2 不动的东西

- ChatController、AgentService、OrchestratorService：零改动
- BaseAgent、ReActAgent、PlanExecuteAgent：零改动
- DelegateAgentTool：零改动
- 前端 ChatStore、ChatView、MessageBubble、PlanCard：零改动
- 数据库迁移：不需要新迁移（AgentEntity 已有 agentType 字段）

## 3. WebSocket 协议设计

### 3.1 帧结构

```json
{
  "type": "消息类型",
  "seq": 1,
  "ts": 1716566400000,
  "payload": { }
}
```

| 字段 | 说明 |
|------|------|
| type | 消息类型标识 |
| seq | 单调递增序号，用于去重和排序 |
| ts | 毫秒时间戳 |
| payload | 类型特定的数据体 |

### 3.2 下行消息（平台 → 本地 Agent）

| type | 说明 | 触发时机 |
|------|------|---------|
| chat_request | 要求 Agent 处理用户消息 | 用户在单聊中发消息 |
| delegation_request | Orchestrator 分配的子任务 | 群聊中 Orchestrator 调度 |
| stop_request | 要求 Agent 停止当前生成 | 用户点击停止按钮 |
| context_update | 推送对话历史上下文 | 新会话开始时 |

chat_request payload：

```json
{
  "message": "用户消息",
  "conversationId": "conv_123",
  "systemPrompt": "系统提示词...",
  "context": [
    { "role": "user", "content": "..." },
    { "role": "assistant", "content": "..." }
  ],
  "workDir": "/home/dev/project",
  "replyTo": null
}
```

### 3.3 上行消息（本地 Agent → 平台）

直接映射到现有 SSE 事件，平台透明转发到聊天流：

| type | 对应 SSE 事件 | payload 关键字段 |
|------|-------------|-----------------|
| text | text | delta, turnId |
| tool_call | tool_call | toolName, args, callId |
| tool_result | tool_result | callId, output |
| artifact_preview | artifact_preview | artifactId, type, previewUrl |
| progress | delegation_progress | status, summary |
| done | done | turnId, finishReason, tokenUsage |
| error | error | code, message |

### 3.4 控制消息

| type | 方向 | 说明 |
|------|------|------|
| register | 本地→平台 | 注册请求：agentName, capabilityTags, workDir, workspace |
| registered | 平台→本地 | 注册确认：agentId |
| ping / pong | 双向 | 心跳，30s 间隔 |
| status | 本地→平台 | 状态更新：agentId, status |

## 4. 平台侧设计

### 4.1 新增文件

```
mateclaw-server/src/main/java/vip/mate/agent/bridge/
├── AgentBridgeManager.java         # WebSocket 连接生命周期 + 路由
├── BridgedAgent.java               # 继承 BaseAgent，远端执行
├── AgentBridgeWsHandler.java       # Spring WebSocket Handler
├── AgentBridgeProtocol.java        # 协议帧编解码 + 校验
└── model/
    ├── BridgeFrame.java            # 协议帧 DTO
    ├── BridgeSession.java          # WebSocket 会话包装
    └── BridgeAgentRegistration.java # 注册请求 DTO
```

### 4.2 AgentBridgeManager

职责：

- **连接注册表**：ConcurrentHashMap<String, BridgeSession>，key=agentId
- **路由**：根据 agentId 找到对应 WebSocket 会话，下发消息
- **心跳管理**：30s 未收到 pong → 标记离线，60s → 关闭会话 + 清理
- **会话绑定**：握手成功创建 BridgeSession，收到 register 帧绑定 agentId

```java
public class AgentBridgeManager {
    BridgeSession onConnect(WebSocketSession wsSession);
    void onRegister(String sessionId, BridgeAgentRegistration reg);
    CompletableFuture<BridgeFrame> send(String agentId, BridgeFrame frame);
    void onDisconnect(String sessionId);
    boolean isOnline(String agentId);
}
```

### 4.3 BridgedAgent

继承 BaseAgent，重写 chatStream()：

```java
public class BridgedAgent extends BaseAgent {
    private final AgentBridgeManager bridgeManager;

    @Override
    public Flux<String> chatStream(String userMessage, String conversationId) {
        BridgeFrame request = BridgeFrame.of("chat_request", Map.of(
            "message", userMessage,
            "conversationId", conversationId,
            "systemPrompt", this.systemPrompt
        ));
        return bridgeManager.send(agentId, request)
            .flatMapMany(response -> bridgeManager.createResponseFlux(agentId));
    }
}
```

对外暴露与 ReActAgent 完全一致的接口，ChatController 和前端零改动。

### 4.4 Agent 自动注册流程

```
本地 Agent                       平台
────────                         ────
WebSocket 握手 ────────────────→ 创建 BridgeSession (agentId=null)

register 帧 ───────────────────→ 查询 mate_agent WHERE name=X
  {agentName, capabilityTags}    ├─ 未找到 → 自动创建 AgentEntity
                                 │   (agentType=local_cli, enabled=true)
                                 └─ 已存在 → 复用已有 agentId
                                 绑定 agentId → BridgeSession

← registered 帧 ──────────────── {agentId}

status 帧 ─────────────────────→ 更新 AgentEntity.agentStatus = AVAILABLE

就绪，等待 chat_request ────────┘
```

### 4.5 配置项

```yaml
mateclaw:
  bridge:
    heartbeat-interval: 30s
    heartbeat-timeout: 60s
    max-connections-per-workspace: 20
    auto-register: true
```

## 5. 本地侧设计

### 5.1 模块结构

```
agent-bridge-client/          # npm 包
├── bridge-client.js          # WebSocket 连接 + 协议编解码
├── adapters/
│   ├── adapter-interface.js  # CLI 适配器接口定义
│   ├── claude-code.js        # Claude Code CLI 适配器
│   ├── opencode.js           # OpenCode CLI 适配器
│   └── generic.js            # 通用 stdio 适配器
└── cli.js                    # 命令行入口
```

### 5.2 CLI 适配器接口

```javascript
{
  spawn(workDir, env): ChildProcess,
  formatInput(bridgeFrame): string,
  parseOutput(cliOutputLine): BridgeFrame[],
  stop(process): void
}
```

### 5.3 命令行使用

```bash
# Claude Code 接入
claude --bridge wss://agenthub.example.com/ws/agent-bridge \
       --token $AGENTHUB_TOKEN \
       --name "我的Claude" \
       --work-dir /home/dev/project

# OpenCode 接入
opencode --bridge wss://agenthub.example.com/ws/agent-bridge \
         --token $AGENTHUB_TOKEN \
         --name "CodeReviewBot"

# 通用桥接脚本
npx agent-bridge-client \
  --url wss://agenthub.example.com/ws/agent-bridge \
  --token $AGENTHUB_TOKEN \
  --name "自定义Agent" \
  --adapter generic \
  --cmd "my-agent --interactive"
```

## 6. 单聊数据流

```
用户浏览器                      AgentHub 平台                    本地机器
─────────                      ────────────                    ────────

[用户选择本地 Agent，发送消息]
    │
    ├─ POST /api/v1/chat/stream ──→ AgentService.chatStream()
    │                               → BridgedAgent.chatStream()
    │                               → BridgeManager.send()
    │                               → 下发 chat_request ──────→│
    │                                                          ├─ ClaudeCodeAdapter
    │                                                          │   .formatInput()
    │   ← SSE: text ────────────── ← text 帧 ──────────────── ←  claude CLI 执行
    │   ← SSE: tool_call ───────── ← tool_call 帧 ─────────── ←
    │   ← SSE: tool_result ─────── ← tool_result 帧 ───────── ←
    │   ← SSE: done ────────────── ← done 帧 ──────────────── ←

[消息气泡完整展示 + 操作按钮]
```

## 7. 群聊数据流

### 7.1 群聊角色

```
群聊会话 (conversationType="group")
├─ 👤 用户 (Human)
├─ 🤖 Orchestrator (agentType="orchestrator")
├─ 🤖 Claude-4.5 (托管 ReActAgent)
├─ 🖥 我的Claude (本地 BridgedAgent)    ← WebSocket
└─ 🖥 CodeReviewBot (本地 BridgedAgent)  ← WebSocket
```

### 7.2 调度流程

```
用户发消息 → Orchestrator 收到
  → LLM 分析 → 生成 Plan
  → SSE: orchestrator_plan (前端展示 PlanCard)

  → DelegateAgentTool.delegateParallel([
      {agent:"我的Claude", task:"设计数据库Schema"},
      {agent:"CodeReviewBot", task:"审查API接口"},
      {agent:"Claude-4.5", task:"生成前端页面"}
    ])

  → 并行分派:
    ├─ AgentService.chat("我的Claude")
    │   → BridgedAgent → WebSocket → 本地执行 → delegation_progress → SSE
    ├─ AgentService.chat("CodeReviewBot")
    │   → BridgedAgent → WebSocket → 本地执行 → delegation_progress → SSE
    └─ AgentService.chat("Claude-4.5")
        → ReActAgent → 平台内 LLM 调用

  → 全部完成 → Orchestrator 聚合 → SSE: done (汇总报告)
```

### 7.3 关键设计点

- DelegateAgentTool 对 BridgedAgent 零感知差异——都通过 AgentService.chat() 调用
- 同一 delegateParallel 中本地 Agent 和托管 Agent 可混合并行
- 子 Agent 的产出在聊天流中作为独立消息气泡展示（带 Agent 头像+名称）
- 上下文字段仅包含当前 conversation 的历史，复用已有窗口裁剪逻辑

## 8. 安全与鉴权

### 8.1 连接鉴权

- WebSocket 握手阶段 JWT Bearer Token 校验
- 用户通过 PAT（Personal Access Token）机制生成接入 Token
- Token scope 包含 `agent:bridge`

### 8.2 Agent 身份绑定

```
register 帧到达 →
  ├─ 从 JWT 获取 userId + workspaceId
  ├─ 查询 mate_agent WHERE name=X AND workspace_id=Y
  │   ├─ 已存在 AND creatorUserId=当前用户 → 复用（重连）
  │   ├─ 已存在 AND creatorUserId≠当前用户 → 拒绝
  │   └─ 不存在 → 自动创建 AgentEntity
  └─ 绑定 agentId → BridgeSession
```

### 8.3 安全措施

| 风险 | 缓解措施 |
|------|---------|
| 恶意 CLI 发送伪造事件 | seq 单调递增校验、必填字段校验、单帧 ≤ 64KB |
| 未授权 Agent 接入 | WebSocket JWT 校验 + Agent 名称所有权校验 |
| 连接劫持 | TLS WebSocket (wss://) + Token 刷新机制 |
| 数据泄漏 | context 仅含当前 conversation 历史 |

## 9. 前端改动

| 文件 | 改动 | 改动量 |
|------|------|--------|
| AgentManageView.vue | 本地 Agent 卡片增加 🖥 图标、在线状态、工作目录 | ~10 行 |
| AgentDetailView.vue | 显示 local_cli 的连接信息 | ~10 行 |
| SettingsView.vue | 新增"本地 Agent 接入"区块（PAT 生成 + 使用说明） | ~50 行 |
| api/auth.js | 新增 createPat / listPats / revokePat | ~15 行 |
| naive-theme.js | 在线/离线状态色 | ~3 行 |

总计约 90 行前端改动。

## 10. 实现路径

```
Phase 1 — 平台侧基础通道         Phase 2 — 本地客户端           Phase 3 — 体验完善
──────                        ────────                      ────────
├─ AgentBridgeManager         ├─ agent-bridge-client (npm)   ├─ 前端 PAT 管理 UI
├─ BridgedAgent               ├─ claude-code adapter         ├─ Agent 选择器本地标识
├─ AgentBridgeWsHandler       ├─ opencode adapter            ├─ 断线自动重连 UX
├─ 协议帧定义                  ├─ generic adapter            ├─ 本地 Agent 心跳监控
├─ WebSocket endpoint          ├─ CLI 启动入口               └─ 连接日志与诊断
└─ 自动 Agent 注册             └─ 使用文档
```
