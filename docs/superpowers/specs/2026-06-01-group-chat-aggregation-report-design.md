# 群聊模式主 Agent 结果聚合与汇报 — 设计方案

**日期:** 2026-06-01
**状态:** 待审批

## 1. 概述

群聊模式中，子 Agent 完成任务后系统缺少主 Agent（Agent01）的结果聚合与汇报机制。本设计在 DAG 调度全部完成后，回调 Agent01 生成自然语言总结报告，汇总成功/失败/等待状态，并在聊天中提供明确的下一步引导。

## 2. 触发时机

当所有子 Agent 到达终态（COMPLETED / FAILED / WAITING），即 `handleNodeCompletion` 中 `streamTracker.completeAndConsumeIfLast()` 返回 `allDone() == true` 时触发聚合。

```
当前流程:
  allDone() → broadcast "done" → 结束

新流程:
  allDone() → aggregateAndReport() → broadcast summary → broadcast "done" → 结束
```

## 3. 聚合流程

`aggregateAndReport()` 内部步骤：

1. 收集本轮所有子 Agent 的消息（按 senderAgentId 查询 assistant 消息）
2. 构建聚合 prompt（调用 `buildAggregationPrompt()`）
3. 加载历史消息作为上下文
4. 调用 `GroupOrchestratorService.callOrchestrator()` 让 Agent01 生成总结
5. Agent01 流式输出作为 `content_delta` 广播，附带 `agentName: "Agent01"` 和 `isAggregation: true`
6. 保存总结消息到数据库（senderAgentId 指向 Agent01）
7. 广播 `done` 事件

关键约束：聚合阶段 Agent01 的输出不走 `collectLine()` / DAG 调度路径，直接作为普通文本流式广播，避免无限循环。

## 4. 聚合 Prompt 设计

在 `GroupOrchestratorService` 中新增 `buildAggregationPrompt()`：

```
你是一个任务协调者。以下是本轮任务分配的执行结果汇总，请撰写一份简明的总结报告。

## 原始任务
<用户最初发送的任务消息>

## 子任务执行结果

### ✅ Agent名称 — 已完成
**任务：** 任务描述
**输出：**
<完整输出文本，超过4000字符截断>

### ❌ Agent名称 — 执行失败
**任务：** 任务描述
**错误：** 错误信息

### ⏸ Agent名称 — 等待中（上游失败）
**任务：** 任务描述
**原因：** 依赖的 XXX 执行失败

---

请总结：
1. 本轮整体完成情况（成功数/失败数/等待数）
2. 已完成任务的关键成果
3. 失败任务的原因和建议下一步
4. 等待中的任务说明
```

历史消息通过 `callOrchestrator` 的 `history` 参数传入，Agent01 具有多轮上下文感知。

## 5. AgentMentionDispatcher 改动

### 5.1 handleNodeCompletion 变更

```java
// 改前：
if (cr.allDone()) {
    streamTracker.broadcastObject(conversationId, "done", ...);
}

// 改后：
if (cr.allDone()) {
    aggregateAndReport(conversationId, nodeMap);
}
```

### 5.2 新增 aggregateAndReport() 方法

约 50 行，核心逻辑：

1. 从 `nodeMap` 遍历所有节点，收集状态和任务描述
2. 查询数据库获取各节点的完整输出消息
3. 组装 prompt → 调 Agent01 → 流式广播 content_delta
4. 保存总结消息
5. 错误降级：Agent01 调用失败时生成简单系统文本总结
6. 最后广播 `done`

### 5.3 新增依赖注入

```java
private final GroupOrchestratorService groupOrchestratorService;
```

### 5.4 DagState 扩展

```java
record DagState(Map<String, TaskNode> nodeMap, Semaphore semaphore,
                String originalTaskMessage) {}
```

### 5.5 ChatController 传参

`executeCollected` 调用时传入原始任务消息文本。

## 6. 错误处理

| 场景 | 处理 |
|------|------|
| Agent01 聚合调用失败 | 降级为系统文本："本轮共 X 个任务：Y 成功，Z 失败，W 等待中" |
| 子 Agent 消息为空 | 仍调 Agent01，prompt 中说明无输出，仅基于任务描述和历史给建议 |
| 聚合 stream 中途断开 | 已接收部分保存为消息，发 `done`，不阻塞后续交互 |
| 聚合期间用户发新消息 | 新消息触发 `resetForTurn` 清理 DAG → 聚合线程检测 DAG 已清除 → 跳过 |
| 全部子 Agent 为 WAITING | prompt 如实反映"所有任务均未执行"，Agent01 建议重新规划 |
| 单节点输出超长（>4000 字符） | 截断至 4000 字符，保留首尾，标注省略 |

## 7. 改动清单

| 层 | 文件 | 变更 | 估算 |
|----|------|------|------|
| 后端 | `AgentMentionDispatcher.java` | 注入 GroupOrchestratorService；新增 aggregateAndReport()；DagState 增加字段；handleNodeCompletion 分支改为聚合回调 | ~60 行 |
| 后端 | `GroupOrchestratorService.java` | 新增 buildAggregationPrompt() | ~25 行 |
| 后端 | `ChatController.java` | 传递 originalTaskMessage 给 mentionDispatcher | ~3 行 |
| 前端 | 无需改动 | 聚合输出走现有 content_delta SSE 通道 | 0 |

## 8. 不变模块

- ChatStreamTracker — 完全复用
- AgentMentionDispatcher 的 DAG 调度逻辑（collectLine / executeCollected / scheduleNode / continueNode）— 不变
- arther-agent / agents.yml — 零改动
- 前端 ChatView / MessageBubble / orchestrator store — 零改动

## 9. SSE 事件

无新增事件。聚合输出复用 `content_delta` 事件，附带 `agentName: "Agent01"` 和 `isAggregation: true` 标记。
