# Orchestrator 多 Agent 调度执行引擎 — 设计规格

**日期:** 2026-05-23  
**状态:** 已批准

## 1. 概述

当前 `orchestrator/` 包已有 REST API、数据库表和事件发布器，但缺少核心执行引擎——即读取任务计划、按依赖关系分派子 Agent、追踪完成状态、聚合结果的调度循环。

此次新增 `OrchestratorEngine` 组件填补这一空白。

## 2. 组件架构

### 2.1 新建文件

```
orchestrator/engine/
  └── OrchestratorEngine.java       ← 新增 @Component
```

### 2.2 修改文件

| 文件 | 变更 |
|------|------|
| `OrchestratorService.java` | 注入 `OrchestratorEngine`；`createTask()` 末尾异步调用 `engine.execute(task)`；`retryAssignments()` 中调用 `engine.executeAssignment(a)` |
| `OrchestratorEventPublisher.java` | 更新方法签名，增加 `agentId`、`assignmentId`、`totalAssignments`、`completedAssignments` 等字段 |

### 2.3 依赖注入

| 依赖 | 用途 |
|------|------|
| `OrchestratorTaskMapper` | 更新任务状态/进度 |
| `OrchestratorAssignmentMapper` | 查询分派、更新状态/结果 |
| `ConversationService` | `createChildConversation()` 创建子会话 |
| `ConversationMapper` | 查询父会话（获取 username、workspaceId） |
| `AgentService` | `chatStream(agentId, goal, convId)` → `Flux<String>` |
| `MessageMapper` | 写入聚合汇总消息（message_type='system'） |
| `GroupConversationMapper` | 读取 `max_parallel_tasks`、`failurePolicy` |
| `OrchestratorEventPublisher` | 广播 SSE 事件 |

### 2.4 EventPublisher 更新后签名

```java
// 每分派生命周期事件
publishDelegationProgress(String conversationId, Long assignmentId, Long agentId,
    String agentName, String status, String summary)

// 任务级生命周期事件
publishPlanUpdate(String conversationId, Long taskId, String title,
    String status, int totalAssignments, int completedAssignments, int failedAssignments)
```

## 3. 执行流程

### 3.1 主循环 `execute(task)`

1. 加载任务下所有分派，按 `stepOrder` 排序
2. 更新 `task.status = RUNNING`，`task.startedAt = now`
3. 通过 `task.conversationId` 查询父会话获取 `username`、`workspaceId`；从 `mate_group_conversation` 读取 `maxParallelTasks`（默认 8）和 `failurePolicy`（默认 `fail_tolerant`）
4. 构建依赖图：将 `dependencyOn` 字段（指向另一个分派的 `stepOrder`）解析为分派 ID → 依赖分派 ID 集合
5. 创建 `Semaphore(maxParallelTasks)` 控制并行上限
6. 创建 `ConcurrentHashMap<Long, CompletableFuture<Void>>` 追踪每个分派的 future（取消用）
7. 循环调度：找出所有依赖已完成的"就绪"分派 → `CompletableFuture.runAsync(runAssignment, virtualThreadExecutor)` → 获取信号量许可
8. 每个 future 完成（成功或失败）后释放信号量，触发下一轮就绪检查
9. 全部分派完成后：聚合 `resultSummary` → 创建 `message_type='system'` 汇总消息 → 更新 `task.status=COMPLETED, aggregationMessageId, completedAt` → 广播 `orchestrator_plan` 完成事件

### 3.2 单分派执行 `executeAssignment(a)`

1. 检查取消标志；若已取消则标记 `CANCELLED` 并返回
2. 更新 `a.status = RUNNING`，`a.startedAt = now`
3. 使用父会话的 `username` 和 `workspaceId` 调用 `ConversationService.createChildConversation(taskId + "-" + assignmentId, agentId, username, workspaceId, parentConversationId)`
4. 订阅 `AgentService.chatStream(agentId, goal, childConvId)`，用 `Flux.collectList().block(Duration.ofSeconds(300))` 收集完整回复
5. 成功：设置 `resultSummary`（截断至 4000 字符），`status = COMPLETED`，广播 `delegation_progress(status=completed)`
6. 超时：设置 `errorMessage = "timeout"`，`status = FAILED`
7. 异常：设置 `errorMessage`，`status = FAILED`；若 `retryCount < 1`，重置为 `pending` 并递增 `retryCount`（由主循环重新调度执行）
8. 更新分派行，递增 `task.completedAssignments` 或 `task.failedAssignments`

### 3.3 依赖图

- `dependencyOn` 字段存储依赖分派的 `stepOrder`（非 ID），在构建依赖图时解析为分派 ID
- 若分派 A（stepOrder=3）的 `dependencyOn=2`，则 A 依赖 stepOrder=2 的分派
- `dependencyOn` 为 null 的分派无依赖，初始即可执行
- 循环依赖检测：构建时若检测到环，抛出 `MateClawException`

## 4. 取消机制

- `OrchestratorEngine` 持有 `ConcurrentHashMap<Long, CompletableFuture<Void>>` + `volatile boolean cancelled`
- `OrchestratorService.cancelTask()` 调用 `engine.cancel(taskId)`：设置取消标志 → 遍历 futures 调用 `future.cancel(true)` → 批量更新未完成分派为 `cancelled`
- 每个分派在开始前和 agent 调用返回后检查取消标志

## 5. 事件 SSE 格式

```
event: delegation_progress
data: {"agentName":"CodeBot","agentId":5,"assignmentId":12,"status":"running","summary":"Working on header..."}

event: orchestrator_plan
data: {"taskId":101,"conversationId":5,"title":"Build landing page","status":"running","totalAssignments":3,"completedAssignments":1,"failedAssignments":0}
```

| 事件 | 触发时机 |
|------|---------|
| `orchestrator_plan` (status=running) | 任务开始执行 |
| `delegation_progress` (status=running) | 分派开始执行 |
| `delegation_progress` (status=completed) | 分派成功完成 |
| `delegation_progress` (status=failed) | 分派失败（重试耗尽后） |
| `orchestrator_plan` (status=completed) | 全部分派完成 |
| `orchestrator_plan` (status=failed) | 任务级失败（fail_fast 触发） |
| `orchestrator_plan` (status=cancelled) | 任务被取消 |

## 6. 关键决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 线程模型 | JDK 21 虚拟线程 + `Semaphore` | 遵循现有 `DelegateAgentTool` 和 `AsyncSecurityConfig` 模式 |
| 并行控制 | `Semaphore(maxParallelTasks)` | 虚拟线程无法限制并发数，信号量精确控制 |
| 分派调度 | 依赖驱动 | 支持复杂的 DAG 执行拓扑，不限于简单串/并行 |
| 执行独立性 | 不复用 `DelegateAgentTool` | 解耦工具语义与调度语义 |
| 分派数据源 | 预创建的 assignment 行 | `createTask()` 已展开步骤，assignment 行承载执行状态 |

## 7. 错误处理与重试

- 单分派失败自动重试 1 次（`retryCount` 上限 1）
- 重试由主循环重新调度：检测到 `status=FAILED && retryCount < 1` → 重置为 `pending` → 下一轮就绪检查时重新提交
- 超时 300 秒/分派（硬编码常量，后续可配置化）
- `failurePolicy = fail_fast`：任一分派最终失败立即取消所有进行中的分派
- `failurePolicy = fail_tolerant`：分派失败不影响其他分派继续

## 8. 测试策略

- **单元测试**：`OrchestratorEngine` 依赖图构建逻辑（mock 所有外部依赖）
- **集成测试**：端到端执行一个 3 分派任务（mock `AgentService.chatStream` 返回预设 Flux）
- **边界测试**：空分派列表、循环依赖检测、超时触发、取消中断、重试耗尽
