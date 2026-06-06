# DAG 暂停恢复机制 — 设计方案

**日期:** 2026-06-01
**状态:** 待审批

## 1. 概述

当前 DAG 调度中，上游 Agent 完成后自动触发下游，用户没有机会审查上游输出。本设计引入 READY 状态和用户确认机制：上游完成后下游标记 READY 等待用户确认，暂停期间用户可与已完成的 Agent 自由对话，满意后点击"继续执行"启动下游。

## 2. 交互流程

```
用户: "设计登录页并实现"
  ↓
Agent01: @设计Agent 设计登录页
         @前端Agent [after:设计Agent] 根据设计稿实现
  ↓
调度器: 设计Agent (入度=0) → 启动
        前端Agent (inDegree=1) → PENDING
  ↓
设计Agent 完成 → COMPLETED
  ↓  ✨ 不自动调度下游
   前端Agent inDegree→0 → 标记 READY
   SSE: agent_ready {agentName:"前端Agent", dependsOn:"设计Agent"}
  ↓
前端展示:
  ┌─ 设计Agent ─────────────────────────┐
  │ 这是设计稿 [预览]...             ✅ │
  └─────────────────────────────────────┘
  ┌─ 前端Agent ─────────────────────────┐
  │ ⏸ 就绪，等待确认                    │
  │ [▶ 继续执行]                        │
  └─────────────────────────────────────┘
  ↓ 用户可追问设计Agent
用户: "@设计Agent 按钮改大一点"
  ↓ (直接派发，不走调度器)
设计Agent: "已调大按钮..."
  ↓
用户满意，点击 [▶ 继续执行]
  ↓ POST /dag/continue → continueNode("前端Agent")
前端Agent 启动 → RUNNING → COMPLETED
```

## 3. 状态模型变更

| 状态 | 含义 | 触发 |
|------|------|------|
| PENDING | 等待上游完成 | DAG 构建时 inDegree>0 |
| RUNNING | 正在执行 | scheduleNode 捡起 |
| COMPLETED | 执行成功 | Agent 正常结束 |
| FAILED | 执行失败 | Agent 异常/超时 |
| **READY** | 上游已完成，等待用户确认 | inDegree 归零后标记 |
| WAITING | 上游失败，永不执行 | 上游 FAILED 后 BFS 传递 |

## 4. 架构改动

### 4.1 AgentMentionDispatcher.java

**handleNodeCompletion 逻辑变更：**

```
改前: COMPLETED → inDegree-- → 若归零 → scheduleNode (自动)
改后: COMPLETED → inDegree-- → 若归零 → 标记 READY
                                         → SSE: agent_ready
                                         → 等待 continueNode()
```

**新增方法：**

```java
// 用户确认后调用，启动 READY 节点
public void continueNode(String conversationId, String agentName, Semaphore semaphore)
```

**activeDags 缓存：** 暂停的 DAG 需要跨请求保持。用 `ConcurrentHashMap<String, DagState>` 存储活跃 DAG（含 nodeMap、semaphore）。

**DAG 暂停期间 @指名处理：** 用户 `@Agent名 消息` 直接走 `spawnSingleAgent()` 立即执行，不走收集器。完成后不触发 DAG 回调。

**新回合清理：** 用户发新任务触发 Orchestrator → `resetForTurn` 清理活跃 DAG → 所有 READY/PENDING 节点标记 CANCELLED。

### 4.2 ChatController.java

新增端点：

```java
@PostMapping("/conversations/{conversationId}/dag/continue")
public R<Void> continueDag(@PathVariable String conversationId,
                           @RequestBody Map<String, String> body) {
    String agentName = body.get("agentName");
    mentionDispatcher.continueNode(conversationId, agentName, resolveSemaphore(conversationId));
    return R.ok();
}
```

### 4.3 前端 SSE 处理

`chat.js` 新增 `agent_ready` 事件：

```js
sse.on('agent_ready', (data) => {
    const agentId = agentStreams.value.get(data.agentName)
    if (agentId) {
        updateMessage(agentId, { status: 'ready', dependsOn: data.dependsOn })
    }
})
```

### 4.4 MessageBubble.vue — READY 状态 UI

```html
<div v-else-if="isReady" class="msg-status ready">
  就绪，等待确认
  <NButton size="tiny" type="primary" @click="emit('continueDag', message)">
    ▶ 继续执行
  </NButton>
</div>
```

按钮点击事件冒泡到 ChatView，调用 `POST /dag/continue`。

## 5. SSE 事件

| 事件 | 字段 | 触发时机 |
|------|------|---------|
| `agent_ready` | agentName, dependsOn | inDegree 归零，等待确认 |

## 6. 改动清单

| 层 | 文件 | 变更 |
|----|------|------|
| 后端 | `AgentMentionDispatcher.java` | READY 状态；handleNodeCompletion 不自动调度；新增 continueNode()、spawnSingleAgent()、activeDags |
| 后端 | `ChatController.java` | 新增 `POST /dag/continue`；DAG 活跃时 @指名直接派发 |
| 前端 | `chat.js` | 处理 `agent_ready` 事件 |
| 前端 | `MessageBubble.vue` | READY 渲染 + 按钮 |
| 前端 | `ChatView.vue` | continueDag 事件处理，调 API |

## 7. 边界处理

| 场景 | 处理 |
|------|------|
| 多个 READY 节点 | 用户可任意顺序点击继续 |
| 暂停中发新任务 | resetForTurn 清理旧 DAG，启动新回合 |
| 用户关闭页面 | DAG 内存驻留；下次 resetForTurn 清理 |
| READY 节点上游失败 | BFS 传播 WAITING，跳过 READY 标记 |
| 连续点击两次继续 | continueNode 检查 status==READY，幂等安全 |
