# 群聊模式 DAG 依赖调度 — 设计方案

**日期:** 2026-06-01
**状态:** 待审批

## 1. 概述

群聊模式中，AgentMentionDispatcher 对所有 @AgentName 行立即并发派发，无视任务间依赖关系。本设计在 AgentMentionDispatcher 内部新增轻量 DAG 编排器，通过异步回调驱动并行分发，确保依赖关系严格执行。

## 2. 架构

### 2.1 当前流程

```
Agent01 输出 → lineBuffer 逐行解析:
  @设计Agent 设计首页UI    → dispatchIfComplete() → 立即启动虚拟线程
  @前端Agent 等设计完成后实现 → dispatchIfComplete() → 立即启动虚拟线程
  ❌ 两个 Agent 同时执行，前端Agent 拿不到设计输出
```

### 2.2 改进流程

```
Agent01 输出 → lineBuffer 逐行解析:
  所有 @行 收集到 List<DagTask>
  ↓
  Agent01 流结束 → executeCollected()
  ↓
  buildDag(): 解析 [after:XX] 标记，构建 TaskNode + 依赖图
  ↓
  scheduleReady(): 入度=0 的节点启动
  ↓
  回调 onNodeComplete(): 下游节点 inDegree--, 归零时 scheduleNode()
  ↓
  上游失败 → 下游标记 WAITING，不自动执行
```

## 3. 改动清单

| 文件 | 变更说明 | 估算 |
|------|---------|------|
| `agents.yml` | 修改 Agent01 instruction 增加 `[after:AgentName]` 标记规则 | ~5 行 |
| `AgentMentionDispatcher.java` | 新增收集/构建/回调三阶段，轻量 DAG 调度逻辑 | ~80 行 |
| `ChatController.java` | 调用侧从 `dispatchIfComplete` 改为 `collectLine` + `executeCollected` | ~10 行 |

## 4. 详细设计

### 4.1 Agent01 指令变更

`agents.yml` 中 Agent01 的 instruction 输出格式规则改为：

```yaml
- 根据任务复杂度和Agent能力拆解任务，每个子任务一行
- 格式：@Agent名称 [after:依赖Agent名称] 任务描述
- [after:Agent名称] 仅在该子任务依赖其他Agent输出时使用，无依赖则省略
- 每个Agent最多被@一次，一个任务只能依赖一个上游Agent
- 示例：
    @设计Agent 设计首页UI布局
    @前端Agent [after:设计Agent] 根据设计稿实现前端页面
    @后端Agent [after:设计Agent] 根据设计稿实现后端API
  设计Agent先执行，完成后前端Agent和后端Agent可并行执行
```

### 4.2 DAG 调度器核心结构

新增内部类/record 在 AgentMentionDispatcher 中：

```java
// 解析后的任务
record DagTask(String agentName, AgentEntity agent, String task,
               String dependsOnAgentName, String claudeMdPath) {}

// 运行时调度节点
class TaskNode {
    final String agentName;
    volatile int inDegree;          // 剩余依赖数，归零即可执行
    volatile TaskStatus status;     // PENDING / RUNNING / COMPLETED / FAILED / WAITING
    final List<TaskNode> dependents; // 依赖于我的下游节点（回调链）
    final DagTask dagTask;
}
```

### 4.3 收集阶段

```java
// 新方法：收集 @行 但不派发
public boolean collectLine(Long conversationDbId, String conversationId,
                           Map<String, AgentEntity> agentNameMap, String line) {
    // 正则: @AgentName [after:依赖Agent] 任务描述
    // 存入内部 List<DagTask>
    // 返回是否匹配到 @行
}
```

### 4.4 构建阶段

```java
// 新方法：构建 DAG 并启动调度
public void executeCollected(String conversationId, Semaphore semaphore) {
    // 1. 遍历 DagTask → 建立 agentName → TaskNode 映射
    // 2. 遍历 DagTask → 若 dependsOnAgentName != null:
    //    - 查映射表找到上游 dep
    //    - currentNode.inDegree++
    //    - dep.dependents.add(currentNode)
    // 3. 循环依赖检测：若所有节点入度>0 → 抛 MateClawException
    // 4. scheduleReady()
}
```

### 4.5 执行与回调

```java
void scheduleReady(List<TaskNode> nodes) {
    for (TaskNode node : nodes) {
        if (node.status == PENDING && node.inDegree == 0) {
            node.status = RUNNING;
            submitToVirtualThread(node, semaphore, () -> {
                // ... 执行 Agent CLI（复用现有 spawnAndStreamAgent 逻辑）
                onNodeComplete(node);
            });
        }
    }
}

void onNodeComplete(TaskNode node) {
    // 通知下游
    for (TaskNode downstream : node.dependents) {
        if (node.status == COMPLETED) {
            downstream.inDegree--;
            if (downstream.inDegree == 0 && downstream.status == PENDING) {
                scheduleNode(downstream);
            }
        } else {
            // 上游 FAILED → 下游标记 WAITING，inDegree 保持 >0，永不被调度
            downstream.status = WAITING;
            broadcast waiting SSE event
        }
    }
}
```

### 4.6 ChatController 调用侧变更

```java
// 改后
.doOnNext(event -> {
    for (char c : event.content()) {
        if (c == '\n') {
            String line = lineBuffer.toString();
            lineBuffer.setLength(0);
            mentionDispatcher.collectLine(convDbId, convId, agentNameMap, line);
        } else {
            lineBuffer.append(c);
        }
    }
})
.doOnComplete(() -> {
    if (lineBuffer.length() > 0) {
        mentionDispatcher.collectLine(convDbId, convId, agentNameMap, lineBuffer.toString());
    }
    mentionDispatcher.executeCollected(convId, groupSemaphore);
})
```

### 4.7 语义调整

| 改变前 | 改变后 |
|--------|--------|
| `dispatchIfComplete()` 立即派发 | `collectLine()` 仅收集，不派发 |
| 无依赖概念 | `executeCollected()` 构建 DAG 后按拓扑序调度 |
| `spawnAndStreamAgent()` 内联在线程 lambda 中 | 提取为可复用方法，供 `scheduleNode` 调用 |

### 4.8 保留不变的

- `resetForTurn()` — 追加清理收集列表
- `Semaphore(maxParallelTasks)` — 并发控制逻辑不变
- SSE broadcast 格式（`agent_message_start`、`content_delta`、`agent_message_complete`）
- 前端 `orchestratorStore.handleDelegationProgress()` — 新增 `waiting` 状态透传即可
- 去重逻辑（同一轮不重复派发同一 Agent）

## 5. 失败处理

| 场景 | 处理 |
|------|------|
| 上游 Agent 完成 | 下游 inDegree--，归零后自动调度 |
| 上游 Agent 失败 | 下游标记 `WAITING`，inDegree 保持>0，永不被调度 |
| 循环依赖 | `buildDag()` 检测到所有节点入度>0 且无就绪节点 → 抛 `MateClawException` |
| 依赖不存在的 Agent | `buildDag()` 中 dependsOnAgentName 查不到映射 → 抛异常（含 Agent 名称） |
| 所有 Agent 都无依赖 | 行为等同于当前（全并行，受 semaphore 控制） |

## 6. SSE 事件扩展

新增 `agent_message_start` 事件中携带依赖信息：

```json
{
  "agentName": "前端Agent",
  "agentId": "5",
  "taskDescription": "根据设计稿实现前端页面",
  "dependsOn": "设计Agent"
}
```

新增 `waiting` 状态事件：

```
event: agent_message_complete
data: {"agentName":"前端Agent","status":"waiting","dependsOn":"设计Agent"}
```

## 7. 前端展示

`MessageBubble.vue` 根据 `agent_message_complete` 的 `status` 渲染：

| status | 图标 | 文字 |
|--------|------|------|
| `completed` | 绿色对勾 | 已完成 |
| `failed` | 红色叉号 | 执行失败 |
| `waiting` | 黄色时钟 | 等待上游 XXX 完成... |

## 8. 不变模块

- OrchestratorEngine — 不涉及，群聊模式不经过此路径
- OrchestratorService REST API — 不涉及
- 前端 ChatView / ConversationSidebar — 不涉及
- arther-agent 代码 — 零改动
