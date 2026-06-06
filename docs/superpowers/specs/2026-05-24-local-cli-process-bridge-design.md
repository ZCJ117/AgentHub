# Local CLI Process Bridge — Design Spec

**版本**: v1.0
**日期**: 2026-05-24
**状态**: 设计已确认
**替代**: 2026-05-24-local-agent-bridge-implementation-design.md（WebSocket 方案废弃）

## 1. 核心决策

| 维度 | 决策 |
|------|------|
| Agent 类型 | 纯本地 Claude Code 或 OpenCode（无云端 Agent） |
| 通信方式 | stdin/stdout JSON 行协议（复用 BridgeFrame） |
| 进程生命周期 | 按对话启动：首条消息 spawn，对话结束 terminate |
| 单聊 | 1 对话 → 1 个 CLI 子进程 |
| 群聊 | 1 对话 → N 个 CLI 子进程（含 1 个 Orchestrator） |
| Orchestrator | 本地 Claude Code 进程 |
| API Key | 继承用户机器环境变量（`ANTHROPIC_API_KEY` 等） |
| CLI 适配 | Node.js 适配器脚本，位于 `mateclaw-dev/adapters/` |

## 2. 架构总览

```
┌─ 前端 ────────────────────────────────────────────────────────┐
│  AgentSelector → 选 Agent → "开始对话"                         │
│  POST /api/v1/conversations → router.push(/chat/:id)          │
└────────────────────────────────────────────────────────────────┘
                              ↓ POST /api/v1/chat/stream
┌─ 后端 (Spring Boot) ──────────────────────────────────────────┐
│  ChatController → AgentService → AgentGraphBuilder             │
│    → BridgedAgent (不改)                                       │
│    → LocalCliProcessManager.spawn(agentId, cliType)            │
│    → sendFrame(stdin, chat_request)                            │
│    → stdoutReaderThread → parse Frame → pushToSink             │
│    → Flux<StreamDelta> → SSE → 前端                            │
└────────────────────────────────────────────────────────────────┘
                    ↓↑ stdin/stdout JSON lines
┌─ 适配器 (Node.js) ────────────────────────────────────────────┐
│  claude-adapter.mjs  /  opencode-adapter.mjs                  │
│  stdin ← BridgeFrame → 调用 CLI SDK → stream output           │
│  stdout → BridgeFrame lines                                   │
└────────────────────────────────────────────────────────────────┘
                    ↓↑ 子进程调用
┌─ 本地 CLI ────────────────────────────────────────────────────┐
│  Claude Code / OpenCode                                       │
└────────────────────────────────────────────────────────────────┘
```

**核心原则**：传输层替换，协议层复用。BridgeFrame JSON 格式、type 枚举、校验逻辑全部复用。唯一变化是载体从 WebSocket TextMessage 变成 Process stdin/stdout。

## 3. 新增组件：LocalCliProcessManager

```java
public class LocalCliProcessManager {
    // agentId → 进程上下文
    private final ConcurrentHashMap<String, LocalProcessContext> processes;

    // 进程上下文
    record LocalProcessContext(
        Process process,
        BufferedWriter stdinWriter,
        Thread stdoutReaderThread,
        FluxSink<AgentService.StreamDelta> responseSink,
        long spawnTime,
        String cliType
    ) {}

    /** 启动 CLI 子进程 */
    Process spawn(String agentId, String cliType, String agentName, String systemPrompt);

    /** 向 stdin 写入 JSON 行 */
    void sendFrame(String agentId, BridgeFrame frame);

    /** 注册响应流（由 BridgedAgent 调用，同现有模式） */
    void registerResponseSink(String agentId, FluxSink<AgentService.StreamDelta> sink);

    /** 移除响应流 */
    void unregisterResponseSink(String agentId);

    /** 终止进程 */
    void terminate(String agentId);

    /** 是否正在运行 */
    boolean isRunning(String agentId);

    /** stdout 读取循环（独立 daemon 线程） */
    void stdoutLoop(String agentId, BufferedReader reader);
}
```

### spawn 逻辑

```java
ProcessBuilder pb = switch (cliType) {
    case "claude_code"  -> new ProcessBuilder("node", "adapters/claude-adapter.mjs");
    case "open_code"    -> new ProcessBuilder("node", "adapters/opencode-adapter.mjs");
};
pb.environment().put("AGENT_ID", agentId);
pb.environment().put("AGENT_NAME", agentName);
pb.environment().put("SYSTEM_PROMPT", systemPrompt);
Process p = pb.start();

// stdin writer
var stdinWriter = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));

// stdout 读取线程
var reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
Thread readerThread = new Thread(() -> stdoutLoop(agentId, reader), "cli-stdout-" + agentId);
readerThread.setDaemon(true);
readerThread.start();

// 保存上下文
processes.put(agentId, new LocalProcessContext(p, stdinWriter, readerThread, null, now, cliType));
```

### 初始化握手

```
后端 spawn CLI 进程
  ↓ 等待首行
适配器 → stdout: { type: "ready" }
  ↓
后端 → stdin: { type: "agent_info", payload: { agentId, agentName, systemPrompt } }
  ↓
适配器 → stdout: { type: "ready" }  // 确认收到
  ↓
进入消息循环，等待 chat_request
```

### 线程安全

- 写入 stdin：由 BridgedAgent 单线程调用，JSON 行级原子
- 读取 stdout：独立 daemon 线程逐行读取 → parse → pushToSink
- 写入和读取分属不同线程，互不干扰

## 4. 适配器脚本

位于 `mateclaw-dev/adapters/`：

```
adapters/
├── claude-adapter.mjs    # Claude Code 适配器
└── opencode-adapter.mjs  # OpenCode 适配器
```

### 工作循环

```
1. 启动 → stdout: { type: "ready" }
2. 等待 stdin 行
3. 收到 { type: "agent_info", payload: { agentId, agentName, systemPrompt } }
   → stdout: { type: "ready" }
4. 等待 stdin 行 → parse BridgeFrame
5. type = "chat_request" → 调用 CLI stream API
6. stream 每个 chunk 包装为 BridgeFrame → stdout:
   - 文本内容       → { type: "text", seq, ts, payload: { delta: "..." } }
   - 工具调用       → { type: "tool_call", seq, ts, payload: { ... } }
   - 工具结果       → { type: "tool_result", seq, ts, payload: { ... } }
   - 产物预览       → { type: "artifact_preview", seq, ts, payload: { ... } }
   - 完成          → { type: "done", seq, ts, payload: { ... } }
7. 继续等待下一个 chat_request (回到步骤 4)
8. 收到 { type: "terminate" } → 退出
```

### claude-adapter.mjs 示意

```javascript
import { exec } from 'child_process';
import readline from 'readline';

const rl = readline.createInterface({ input: process.stdin });

function sendFrame(type, payload) {
    process.stdout.write(JSON.stringify({ type, seq: 0, ts: Date.now(), payload }) + '\n');
}

sendFrame('ready');  // 进程就绪

let agentInfo = null;

rl.on('line', async (line) => {
    const frame = JSON.parse(line);
    switch (frame.type) {
        case 'agent_info':
            agentInfo = frame.payload;
            sendFrame('ready');
            break;
        case 'chat_request': {
            const { message, conversationId, systemPrompt } = frame.payload;
            // 调用 claude CLI (支持 stream)
            const proc = exec(`claude -p "${escapeArg(message)}" --output-format stream-json`, {
                env: { ...process.env, CLAUDE_CODE_SYSTEM_PROMPT: systemPrompt }
            });
            proc.stdout.on('data', (chunk) => {
                sendFrame('text', { delta: chunk.toString() });
            });
            proc.on('close', (code) => {
                sendFrame('done', { exitCode: code });
            });
            break;
        }
        case 'terminate':
            process.exit(0);
    }
});
```

### opencode-adapter.mjs

与 claude-adapter.mjs 结构相同，只是调用命令替换为 `opencode`。具体 CLI 调用方式视 OpenCode 的 SDK/CLI 接口而定（实现时需确认 opencode 的 stream 模式和参数格式）。

## 5. 协议扩展

### 复用现有类型

`chat_request`, `stop_request`, `text`, `tool_call`, `tool_result`, `artifact_preview`, `progress`, `done`, `error`, `ping`, `pong`

### 新增类型

| Type | 方向 | 用途 |
|------|------|------|
| `ready` | 适配器→后端 | 就绪信号 |
| `agent_info` | 后端→适配器 | spawn 后发送 agentId、agentName、systemPrompt |
| `terminate` | 后端→适配器 | 对话结束，进程退出 |
| `delegation_request` | 后端→适配器 | 群聊 Orchestrator 分派子任务 |
| `delegation_response` | 适配器→后端 | 成员 Agent 返回给 Orchestrator |

### 协议校验（继承现有）

| 校验 | 规则 | 失败处理 |
|------|------|---------|
| 行大小 | ≤ 64KB | 丢弃行，日志 warning |
| Type 已知 | type ∈ 已知类型集合 | 丢弃行，日志 warning |
| JSON 有效 | 可解析为 BridgeFrame | 丢弃行，日志 warning |

## 6. 进程并发模型

```
进程表:
┌─────────────────────────────────────────────────────┐
│ agentId → LocalProcessContext                       │
│   ├─ Process process                                │
│   ├─ BufferedWriter stdinWriter                     │
│   ├─ Thread stdoutReaderThread (daemon)             │
│   ├─ FluxSink<StreamDelta> responseSink             │
│   └─ long spawnTime                                 │
└─────────────────────────────────────────────────────┘

单聊: 1 对话 → 1 进程
群聊: 1 对话 → (N 成员 + 1 Orchestrator) 个进程，各进程独立
```

## 7. 错误处理

| 场景 | 后端行为 | 用户可见 |
|------|---------|---------|
| CLI 启动失败（找不到 node/cli） | spawn 抛异常 → Flux.error | 错误气泡："本地 Agent 启动失败" |
| stdout 读取线程异常退出 | completeSink + terminate 进程 | 消息流中断 + "Agent 进程已退出" |
| 适配器返回 error frame | errorSink(errorFrame.payload.message) | 显示错误消息 |
| 用户点 Stop | stdin 写入 `stop_request` frame | 停止生成 |
| 对话关闭/路由切换 | stdin 写入 `terminate`，等待 2s 后 Process.destroy() | 进程退出 |
| 进程僵死（无响应 5 分钟） | Process.destroyForcibly() | 对话报错 |
| Orchestrator 进程崩溃 | 通知所有成员 Agent terminate，对话降级为普通群聊 | 显示错误 |

## 8. 配置

```yaml
mateclaw:
  local-cli:
    node-bin: "node"
    adapters-dir: "adapters"
    timeout-seconds: 300
    force-kill-seconds: 5
```

## 9. 前端改动

### AgentDetailView.vue
- Agent 类型下拉：只保留 `claude_code` 和 `open_code`
- 新增字段 `cliType` 存储到后端
- 隐藏模型选择（对本地 CLI 无用）

### AgentManageView.vue
- 卡片显示 `cliType`（Claude Code / OpenCode 标签）
- 状态指示：绿色（进程运行中）/ 灰色（未运行）

### AgentSelector.vue
- 单聊模式：不变（只是 Agent 类型简化了）
- 群聊模式：Orchestrator 只从 `cliType == claude_code` 的 Agent 中选
- 去掉 `schedulingMode` / `failurePolicy` / `maxParallelTasks`，使用后端默认值

### ConversationSidebar.vue
- 基本不变

## 10. 后端改动

### 新增文件

| 文件 | 用途 |
|------|------|
| `agent/cli/LocalCliProcessManager.java` | 进程 spawn/terminate/stdin-stdout 管理 |
| `adapters/claude-adapter.mjs` | Claude Code Node.js 适配器 |
| `adapters/opencode-adapter.mjs` | OpenCode Node.js 适配器 |

### 新增 Flyway 迁移

```sql
-- V132: 新增 cli_type 字段
ALTER TABLE mate_agent ADD COLUMN cli_type VARCHAR(20);
-- 值: 'claude_code' | 'open_code' | NULL
```

### 修改文件

| 文件 | 改动 |
|------|------|
| AgentEntity.java | +`cliType` 字段（String: `claude_code` / `open_code`） |
| AgentGraphBuilder.java | `local_cli` 分支注入 LocalCliProcessManager 替代 AgentBridgeManager |
| BridgedAgent.java | 构造器新增 LocalCliProcessManager 参数；`chatStructuredStream` 中 local_cli 走 ProcessManager 而非 WebSocket |
| AgentController.java | 接收 `cliType` 字段，透传到 AgentEntity |
| application.yml | 新增 `mateclaw.local-cli.*` 配置 |

### 保留但不修改

现有 WebSocket bridge 包（AgentBridgeManager, AgentBridgeWsHandler, BridgeFrame, BridgeSession 等）**保留，零改动**。未来远程 CLI 场景可在此基础上扩展。新增的 LocalCliProcessManager 是独立组件，不侵入 bridge 包。

WebSocketConfig.java 和 SecurityConfig.java 中已有的 handler 注册和 permitAll 也保留不动。

### 不影响的部分

- ChatController / AgentService / ConversationService：零改动
- OrchestratorService / DelegateAgentTool：零改动（通过 AgentService 透明调用）
- SSE 流式输出：零改动
- 前端 ChatView / MessageBubble / Composer：零改动

## 11. 场景时序

### 单聊

```
1. 用户点击"新建对话"
2. AgentSelector 弹出，选一个 Agent（cliType=claude_code）
3. 点击"开始对话"
4. POST /api/v1/conversations → 创建 ConversationEntity
5. router.push(/chat/:conversationId)
6. 用户输入消息，点击发送
7. POST /api/v1/chat/stream { agentId, message, conversationId }
8. AgentService.chatStream() → BridgedAgent.chatStructuredStream()
9. LocalCliProcessManager.isRunning(agentId)? → false
10. spawn(agentId, "claude_code") → ProcessBuilder.start()
11. 适配器 stdout: "ready"
12. 后端 stdin: agent_info frame
13. 适配器 stdout: "ready"
14. 后端 stdin: chat_request frame { message, conversationId, systemPrompt }
15. 适配器调用 claude CLI stream
16. stdout: text frames → pushToSink → SSE → 前端渲染
17. stdout: done frame → completeSink
```

### 群聊

```
1. 用户点击"新建群聊"
2. AgentSelector → 选成员 Agent（3 个 claude_code + 1 个 opencode）
3. 选 Orchestrator（从 claude_code 成员中选 1 个）
4. 输入群聊名称 → "创建群聊"
5. POST /api/v1/conversations/group { title, agentIds[], orchestratorAgentId }
6. 创建 GroupConversation + GroupMember 记录
7. router.push(/chat/:conversationId)
8. 用户输入消息发送
9. AgentService 委托 Orchestrator Agent
10. Orchestrator 分析任务 → 对每个子任务调用 AgentService.chat()（透明调用成员 Agent）
11. 后端为每个成员 Agent spawn CLI 进程（如尚未运行）
12. 各成员各自处理子任务，结果返回给 Orchestrator 聚合
13. Orchestrator stdout: done → 完整结果返回前端
```

## 12. 边界情况

| 场景 | 处理 |
|------|------|
| 同一 Agent 被两个对话同时使用 | 每个对话独立 spawn 进程。对话 A 占用 Agent X 时，对话 B 也使用 Agent X → 后端返回 `409 Conflict`："Agent X 正在被另一个对话使用，请切换对话或等待其完成"。进程绑定对话，不可共享 |
| 用户在一个对话中关闭页面 | 路由切换触发 `terminate`，如果对话仍在活跃；配置可选的 keepalive |
| 平台重启 | JVM shutdown hook → 遍历所有进程 → terminate → destroyForcibly |
| 适配器脚本不存在 | spawn 时检查文件存在性，抛明确异常 |
| 群聊 Orchestrator 也是成员之一 | 允许；进程只 spawn 一次，通过标记区分角色 |
