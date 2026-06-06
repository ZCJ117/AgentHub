# 群聊 Orchestrator 任务分配 — 设计方案

## 概述

在群聊模式中，用户创建群聊勾选多个 Agent 作为成员，发送任务消息后系统自动调用内置 Orchestrator Agent（从 agents.yml 加载的 Agent01），Orchestrator 根据用户任务及 Agent 能力，以 `@Agent名称 任务描述` 格式分配子任务到群聊，前端对 @Agent名称 做高亮组件化展示。

## 架构

```
前端(Vue3) ──POST /api/v1/conversations/group──→ mateclaw-server(18088)
                                                      │
用户发任务消息 ──POST /api/v1/chat/stream────────→ ChatController
                                                      │
                                           检测群聊 → ArtherAgentClient
                                                      │
                                           HTTP POST → arther-agent:8091
                                                      │ /api/v1/chat_stream
                                                      │ agentId=000001(Agent01)
                                                      │
                                           SSE stream ← Agent01 输出 @行
                                                      │
                                           AgentMentionDispatcher 拦截 @行
                                           逐行匹配 → 启动成员 Agent CLI
                                                      │
                                           SSE broadcast → 前端渲染
```

### 关键设计决策

- **只换 Orchestrator 调用方式**：从 local_cli 改为调 arther-agent REST API，群聊 CRUD、@Agent 分发、SSE 广播全部复用
- **arther-agent 无感知群聊**：只收到格式化的 prompt（Agent 列表 + 任务描述），按 agents.yml instruction 输出 @格式
- **mateclaw-server 负责 SSE 中继**：接收 arther-agent SSE 事件，解析后广播到前端 SSE 连接

## 前端改动

### 1. AgentSelector.vue — 移除 Orchestrator 选择器

删除：
- `orchestratorAgentId` ref
- `orchestratorCandidates` computed
- `<NSelect>` 组件
- `handleCreate()` 中 group 分支的 `orchestratorAgentId` 字段

不改动：
- 群聊名称输入、Agent 复选框、创建/取消按钮逻辑保持不变
- 最小 Agent 数量校验（>=2）保持不变

### 2. ConversationSidebar.vue — 去掉 orchestatorAgentId 传参

`handleCreateConversation()` 中 `convStore.createGroup()` 调用的参数去掉 `orchestratorAgentId`。

### 3. useMarkdown.js — 新增 @AgentName 高亮函数

```js
const AGENT_MENTION_RE = /@([^\s,，。；;:：\n]+)/g

export function highlightAgentMentions(text) {
  return text.replace(AGENT_MENTION_RE, '<span class="agent-mention">$&</span>')
}
```

### 4. MessageBubble.vue — 渲染前高亮

```js
const renderedContent = computed(() => {
  if (props.message.messageType === 'text' || props.message.messageType === 'system') {
    const highlighted = highlightAgentMentions(props.message.content || '')
    return renderMarkdown(highlighted)
  }
  return ''
})
```

CSS 追加：

```css
.msg-text :deep(.agent-mention) {
  background: #E8F0FE;
  color: #1A56DB;
  padding: 2px 6px;
  border-radius: 6px;
  font-weight: 600;
  white-space: nowrap;
}
```

## 后端改动

### 5. agents.yml — Agent01 instruction 增加约束

在 "## 输出格式" 小节新增一条规则：

```yaml
- 每个智能体只能被 @ 指派一次。在任务描述中引用其他智能体的依赖关系时，
  去掉 @ 前缀，直接使用智能体名称（例如 "等待 设计Agent 交付设计稿后"）。
```

### 6. ArtherAgentClient.java — 新建

路径：`mateclaw-server/src/main/java/vip/mate/group/client/ArtherAgentClient.java`

基于 Spring WebClient 的 HTTP 客户端：

- `callOrchestrator(userId, prompt)` — POST `/api/v1/chat_stream`，返回 `Flux<String>`（SSE 文本行流）
- `buildOrchestratorPrompt(memberAgents, taskMessage)` — 构造 "可用智能体：\n名称(能力)\n...\n任务请求：xxx" 格式 prompt

请求体：
```json
{
  "agentId": "000001",
  "userId": "<username>",
  "message": "可用智能体：\n研发Agent（后端开发）\n...\n\n任务请求：月底前完成活动报名页..."
}
```

arther-agent base URL：`http://127.0.0.1:8091`（从 application.yml 配置读取，支持环境变量覆盖）。

### 7. ChatController.java — 群聊消息路由到 arther-agent

在现有 `chatStream()` 方法的群聊分支中（`isGroupChat == true`）：

1. 首次消息（conversation 无活跃 Orchestrator 流）→ 调用 `ArtherAgentClient.callOrchestrator()`
2. 将 arther-agent SSE 事件解析为纯文本（从 `data: {"type":"textDelta","text":"..."}` 格式提取）
3. 逐行检测 → 走现有 `AgentMentionDispatcher.dispatchIfComplete()` 流程
4. `content_delta` 事件广播到前端 SSE

关键：复用现有的 `lineBuffer` + `dispatchIfComplete()` 逻辑，只是 Orchestrator 的文本来源从 local_cli 改为 arther-agent SSE。

### 8. GroupConversationService.java — 移除 Orchestrator 创建逻辑

- `createGroup()` 中不再需要 `orchestratorAgentId` 参数和 `findOrCreateDefaultOrchestrator()` 调用
- `generateClaudeMdFiles()` 不再调用（arther-agent 使用 agents.yml 预配置 instruction，不需要临时文件）
- `buildGroupMemberContextPrompt()` 保留（非群聊首次消息场景可能仍需）
- `getGroupConfig()` 中 `orchestratorAgentId` 字段保留但不再使用，向后兼容

## 错误处理

| 场景 | 处理 |
|------|------|
| arther-agent 未启动 (connection refused) | ArtherAgentClient 返回错误 Flux，前端显示 "Orchestrator 服务不可用" |
| Agent01 输出无 @行 | 正常流式输出到前端，不加 Agent 高亮 |
| @Agent名称 在成员列表中找不到 | AgentMentionDispatcher 记录 warn 日志，跳过，不影响其他分发 |
| SSE 中途断开 | ChatStreamTracker 支持重连，前端 useSSE 有超时保护 (30s/90s) |

## 不受影响的模块

- AgentMentionDispatcher — 完全复用
- ChatStreamTracker — 完全复用
- GroupConversationController CRUD 端点 — 不变
- 前端 ChatView / ConversationSidebar（除 AgentSelector）— 不变
- arther-agent 代码 — 零改动（Agent01 已配置好）

## 测试要点

1. 创建群聊→发送任务→看到 Agent01 输出的 @指派行在群聊中
2. @Agent名称 在消息中渲染为蓝色高亮标签
3. 每个 Agent 在一轮输出中只被 @ 一次
4. 成员 Agent 收到指派后正常回复
5. arther-agent 不可用时的错误提示
