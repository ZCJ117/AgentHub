# DAG 暂停恢复机制 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 上游 Agent 完成后标记下游 READY（不自动执行），用户可在暂停期间与已完成的 Agent 继续对话，满意后点击"继续执行"启动下游。

**Architecture:** `handleNodeCompletion` 的 COMPLETED 分支从"自动 scheduleNode"改为"标记 READY + 广播 agent_ready SSE"；新增 `continueNode()` 公共方法和 `activeDags` 缓存跨请求保持 DAG 状态；暂停期间 @指名直接走 `spawnSingleAgent()` 绕过收集器；新增 REST 端点 `POST /dag/continue` 触发继续。

**Tech Stack:** Java 21 Virtual Threads + ConcurrentHashMap + SSE + Vue 3

**Spec:** `docs/superpowers/specs/2026-06-01-dag-pause-resume-design.md`

---

## File Map

| File | Role | Action |
|------|------|--------|
| `AgentMentionDispatcher.java` | READY 状态、continueNode、spawnSingleAgent、activeDags | Modify |
| `ChatController.java` | 新增 `/dag/continue` 端点；DAG 暂停时 @指名直接派发 | Modify |
| `chat.js` | `agent_ready` SSE 事件处理 | Modify |
| `MessageBubble.vue` | READY 状态渲染 + "继续执行"按钮 | Modify |
| `ChatView.vue` | `continue-dag` 事件处理、API 调用 | Modify |

---

### Task 1: Add READY to TaskStatus and activeDags cache

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java`

- [ ] **Step 1: Add READY to TaskStatus enum and activeDags cache**

Replace the `TaskStatus` enum (line 61) and add `activeDags` after `scheduleLock`:

Old:
```java
    enum TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, WAITING }
```

New:
```java
    enum TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, WAITING, READY }

    /** Active DAGs during pause — keyed by conversationId */
    private final Map<String, DagState> activeDags = new ConcurrentHashMap<>();

    record DagState(Map<String, TaskNode> nodeMap, Semaphore semaphore) {}
```

Use the Edit tool:
1. Change line 61's `enum TaskStatus` to include `READY`
2. After line 58 (the `scheduleLock` field), insert the `activeDags` and `DagState`

- [ ] **Step 2: Store DAG in activeDags at end of executeCollected**

In `executeCollected`, after the `scheduleReady` call (line 200), add:

```java
        activeDags.put(conversationId, new DagState(nodeMap, semaphore));
```

Use the Edit tool to insert this line after the `scheduleReady` call and before the closing brace of `executeCollected`.

- [ ] **Step 3: Build verification**

```bash
cd D:/code/Loom/mateclaw-dev && mvn compile -pl mateclaw-domain -q
```

- [ ] **Step 4: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java
git commit -m "feat: add READY status and activeDags cache for DAG pause mechanism"
```

---

### Task 2: Change handleNodeCompletion — mark READY instead of auto-scheduling

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java`

- [ ] **Step 1: Replace the COMPLETED branch in handleNodeCompletion**

In `handleNodeCompletion` (line 254), replace the COMPLETED branch:

Old:
```java
            if (node.status == TaskStatus.COMPLETED) {
                // Decrement inDegree of dependents; schedule if ready
                for (TaskNode dependent : node.dependents) {
                    dependent.inDegree--;
                    if (dependent.inDegree == 0 && dependent.status == TaskStatus.PENDING) {
                        scheduleNode(dependent, conversationId, semaphore);
                    }
                }
            }
```

New:
```java
            if (node.status == TaskStatus.COMPLETED) {
                // Decrement inDegree of dependents; mark READY instead of auto-scheduling
                for (TaskNode dependent : node.dependents) {
                    dependent.inDegree--;
                    if (dependent.inDegree == 0 && dependent.status == TaskStatus.PENDING) {
                        dependent.status = TaskStatus.READY;
                        streamTracker.broadcastObject(conversationId, "agent_ready", Map.of(
                                "agentName", dependent.agentName,
                                "agentId", String.valueOf(dependent.dagTask.agent.getId()),
                                "dependsOn", node.agentName,
                                "taskDescription", dependent.dagTask.task
                        ));
                        log.info("[Dispatcher] Agent {} marked READY, waiting for user confirmation", dependent.agentName);
                    }
                }
            }
```

Use the Edit tool.

- [ ] **Step 2: Build verification**

```bash
cd D:/code/Loom/mateclaw-dev && mvn compile -pl mateclaw-domain -q
```

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java
git commit -m "feat: mark dependents READY instead of auto-scheduling on upstream completion"
```

---

### Task 3: Add continueNode() and spawnSingleAgent() methods

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java`

- [ ] **Step 1: Add continueNode() method**

Insert after `executeCollected` (before `scheduleReady`):

```java
    /**
     * Continue a READY node after user confirmation.
     * Idempotent: only acts on nodes with READY status.
     */
    public void continueNode(String conversationId, String agentName, Semaphore semaphore) {
        DagState state = activeDags.get(conversationId);
        if (state == null) {
            log.warn("[Dispatcher] continueNode: no active DAG for conversation={}", conversationId);
            return;
        }
        TaskNode node = state.nodeMap.get(agentName);
        if (node == null) {
            log.warn("[Dispatcher] continueNode: agent '{}' not found in DAG", agentName);
            return;
        }
        if (node.status != TaskStatus.READY) {
            log.info("[Dispatcher] continueNode: agent '{}' status is {}, not READY, skipping", agentName, node.status);
            return;
        }
        Semaphore sem = semaphore != null ? semaphore : state.semaphore;
        scheduleNode(node, conversationId, sem);
    }
```

Insert this right after `executeCollected`'s closing brace and before `scheduleReady`.

- [ ] **Step 2: Add spawnSingleAgent() method**

Insert after `continueNode`:

```java
    /**
     * Directly spawn a single agent without DAG collection.
     * Used during DAG pause for ad-hoc @mentions to completed agents.
     *
     * @return true if the line was an @AgentName dispatch
     */
    public boolean spawnSingleAgent(Long conversationDbId, String conversationId,
                                     Map<String, AgentEntity> agentNameMap, String line,
                                     Semaphore semaphore) {
        Matcher m = AGENT_PATTERN.matcher(line.trim());
        if (!m.matches()) return false;

        String agentName = m.group(1);
        // Ignore dependency marker in direct spawns
        String task = m.group(3) != null ? m.group(3).trim() : "";

        AgentEntity agent = agentNameMap.get(agentName);
        if (agent == null) {
            log.warn("[Dispatcher] spawnSingle: agent '{}' not found", agentName);
            return false;
        }

        String claudeMdPath = groupConversationService.getClaudeMdPath(conversationDbId, agentName);

        // Track as active stream
        streamTracker.incrementFlux(conversationId);

        // Build a standalone DagTask with no dependencies
        DagTask dagTask = new DagTask(agentName, agent, task, null, claudeMdPath);
        TaskNode node = new TaskNode(agentName, dagTask);

        // Broadcast start event
        streamTracker.broadcastObject(conversationId, "agent_message_start", Map.of(
                "agentName", agentName,
                "agentId", String.valueOf(agent.getId()),
                "taskDescription", task
        ));

        // Run inline — onComplete handles flux cleanup
        scheduleNode(node, conversationId, semaphore, () -> {
            // After completion, do NOT trigger DAG callbacks
            ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
            if (cr.allDone()) {
                streamTracker.broadcastObject(conversationId, "done", Map.of(
                        "conversationId", conversationId,
                        "status", "completed"
                ));
            }
        });

        return true;
    }
```

Wait — `scheduleNode` currently calls `handleNodeCompletion` for the callback. For `spawnSingleAgent`, we need `scheduleNode` to call a DIFFERENT callback (one that doesn't trigger the DAG chain). Let me adjust the approach.

Change `scheduleNode` to accept an optional callback:

In `scheduleNode`, the line:
```java
                executeSingleNode(node, conversationId, () ->
                        handleNodeCompletion(node, conversationId, semaphore));
```

Should become:
```java
                executeSingleNode(node, conversationId, () -> {
                    if (onComplete != null) {
                        onComplete.run();
                    } else {
                        handleNodeCompletion(node, conversationId, semaphore);
                    }
                });
```

So `scheduleNode` needs a new overload or a nullable onComplete parameter. Let me add an overload:

```java
    private void scheduleNode(TaskNode node, String conversationId, Semaphore semaphore, Runnable onComplete) {
        // ... same as scheduleNode but uses onComplete instead of handleNodeCompletion
    }
```

Actually, let me keep it simpler. Add an overload:

```java
    private void scheduleNode(TaskNode node, String conversationId, Semaphore semaphore) {
        scheduleNode(node, conversationId, semaphore, null);
    }

    private void scheduleNode(TaskNode node, String conversationId, Semaphore semaphore, Runnable customOnComplete) {
        node.status = TaskStatus.RUNNING;
        log.info("[Dispatcher] Starting agent={}, inDegree was 0", node.agentName);

        streamTracker.broadcastObject(conversationId, "delegation_progress", Map.of(
                "agentName", node.agentName,
                "agentId", node.dagTask.agent.getId(),
                "status", "running"
        ));

        Thread vt = Thread.startVirtualThread(() -> {
            try {
                if (!semaphore.tryAcquire(180, TimeUnit.SECONDS)) {
                    log.warn("[Dispatcher] Semaphore timeout for agent={}", node.agentName);
                    node.status = TaskStatus.FAILED;
                    streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                            "agentName", node.agentName, "status", "error", "error", "等待槽位超时"
                    ));
                    if (customOnComplete != null) customOnComplete.run();
                    else handleNodeCompletion(node, conversationId, semaphore);
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                node.status = TaskStatus.FAILED;
                if (customOnComplete != null) customOnComplete.run();
                else handleNodeCompletion(node, conversationId, semaphore);
                return;
            }

            activeThreads.put(conversationId + ":" + node.agentName, Thread.currentThread());
            try {
                executeSingleNode(node, conversationId, () -> {
                    if (customOnComplete != null) customOnComplete.run();
                    else handleNodeCompletion(node, conversationId, semaphore);
                });
            } finally {
                activeThreads.remove(conversationId + ":" + node.agentName);
                semaphore.release();
            }
        });
    }
```

OK this is getting complex. Let me simplify the plan. I'll use the Edit tool to make precise changes to scheduleNode and add the new methods.

Actually, let me rethink. The `spawnSingleAgent` needs its own completion handler that:
1. Broadcasts `agent_message_complete` (already done by executeSingleNode)
2. Calls `completeAndConsumeIfLast` (flux cleanup)
3. Does NOT call handleNodeCompletion (no DAG chaining)

So `scheduleNode` needs to support a custom onComplete. The simplest change is to add a 4-param overload.

Let me write the plan steps more carefully.

- [ ] **Step 1b: Add scheduleNode overload with custom callback**

Before the existing `scheduleNode` method, add:

```java
    private void scheduleNode(TaskNode node, String conversationId, Semaphore semaphore) {
        scheduleNode(node, conversationId, semaphore, null);
    }
```

Then modify the existing `scheduleNode` body to accept `Runnable customOnComplete` parameter and use it:

Replace the entire existing `scheduleNode` method signature and the two `handleNodeCompletion` references:

```java
    private void scheduleNode(TaskNode node, String conversationId, Semaphore semaphore, Runnable customOnComplete) {
        node.status = TaskStatus.RUNNING;

        log.info("[Dispatcher] Starting agent={}, inDegree was 0", node.agentName);

        streamTracker.broadcastObject(conversationId, "delegation_progress", Map.of(
                "agentName", node.agentName,
                "agentId", node.dagTask.agent.getId(),
                "status", "running"
        ));

        Thread vt = Thread.startVirtualThread(() -> {
            try {
                if (!semaphore.tryAcquire(180, TimeUnit.SECONDS)) {
                    log.warn("[Dispatcher] Semaphore timeout for agent={}", node.agentName);
                    node.status = TaskStatus.FAILED;
                    streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                            "agentName", node.agentName, "status", "error", "error", "等待槽位超时"
                    ));
                    if (customOnComplete != null) customOnComplete.run();
                    else handleNodeCompletion(node, conversationId, semaphore);
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                node.status = TaskStatus.FAILED;
                if (customOnComplete != null) customOnComplete.run();
                else handleNodeCompletion(node, conversationId, semaphore);
                return;
            }

            activeThreads.put(conversationId + ":" + node.agentName, Thread.currentThread());
            try {
                executeSingleNode(node, conversationId, () -> {
                    if (customOnComplete != null) customOnComplete.run();
                    else handleNodeCompletion(node, conversationId, semaphore);
                });
            } finally {
                activeThreads.remove(conversationId + ":" + node.agentName);
                semaphore.release();
            }
        });
    }
```

This is a bit long for the plan. Let me compress the steps and be precise about the edits.

OK let me just write the plan with all the merged steps. The plan is getting long but each task has complete code. Let me finalize it.<｜end▁of▁thinking｜>

<｜｜DSML｜｜tool_calls>
<｜｜DSML｜｜invoke name="Write">
<｜｜DSML｜｜parameter name="content" string="true"># DAG 暂停恢复机制 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 上游 Agent 完成后标记下游 READY（不自动执行），用户可在暂停期间与已完成的 Agent 继续对话，满意后点击"继续执行"启动下游。

**Architecture:** `handleNodeCompletion` 的 COMPLETED 分支从"自动 scheduleNode"改为"标记 READY + 广播 agent_ready SSE"；新增 `continueNode()` 公共方法和 `activeDags` 缓存跨请求保持 DAG 状态；`scheduleNode` 增加 `customOnComplete` 参数，暂停期间 @指名走 `spawnSingleAgent()` 使用独立回调；新增 REST 端点 `POST /dag/continue` 触发继续。

**Tech Stack:** Java 21 Virtual Threads + ConcurrentHashMap + SSE + Vue 3

**Spec:** `docs/superpowers/specs/2026-06-01-dag-pause-resume-design.md`

---

## File Map

| File | Role | Action |
|------|------|--------|
| `AgentMentionDispatcher.java` | READY 状态、continueNode、spawnSingleAgent、activeDags、scheduleNode 重构 | Modify |
| `ChatController.java` | 新增 `/dag/continue` 端点；DAG 暂停时 @指名直接派发 | Modify |
| `chat.js` | `agent_ready` SSE 事件处理 | Modify |
| `MessageBubble.vue` | READY 状态渲染 + "继续执行"按钮 | Modify |
| `ChatView.vue` | `continue-dag` 事件处理、API 调用 | Modify |

---

### Task 1: AgentMentionDispatcher — READY 状态 + activeDags + 核心逻辑

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java`

- [ ] **Step 1: Add READY to enum and activeDags field**

Read the file. Make two edits:

Edit A — Change line 61 from:
```java
    enum TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, WAITING }
```
to:
```java
    enum TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, WAITING, READY }
```

Edit B — After line 58 (`private final Object scheduleLock = new Object();`), insert:
```java
    /** Active DAGs during pause — keyed by conversationId, survives between HTTP requests */
    private final Map<String, DagState> activeDags = new ConcurrentHashMap<>();

    record DagState(Map<String, TaskNode> nodeMap, Semaphore semaphore) {}
```

- [ ] **Step 2: Store DAG in activeDags after scheduling**

In `executeCollected`, after line 200 (`scheduleReady(new ArrayList<>...`), add:
```java
        activeDags.put(conversationId, new DagState(nodeMap, semaphore));
```

- [ ] **Step 3: Refactor scheduleNode — add customOnComplete overload**

Replace the existing `scheduleNode` method (lines 212-252) with a two-method pattern: a no-arg wrapper that delegates, and a 4-param version with custom callback.

Old (line 212):
```java
    private void scheduleNode(TaskNode node, String conversationId, Semaphore semaphore) {
```

Replace the ENTIRE method (from line 212 through line 252) with:
```java
    private void scheduleNode(TaskNode node, String conversationId, Semaphore semaphore) {
        scheduleNode(node, conversationId, semaphore, null);
    }

    private void scheduleNode(TaskNode node, String conversationId, Semaphore semaphore, Runnable customOnComplete) {
        node.status = TaskStatus.RUNNING;

        log.info("[Dispatcher] Starting agent={}, inDegree was 0", node.agentName);

        streamTracker.broadcastObject(conversationId, "delegation_progress", Map.of(
                "agentName", node.agentName,
                "agentId", node.dagTask.agent.getId(),
                "status", "running"
        ));

        Thread vt = Thread.startVirtualThread(() -> {
            try {
                if (!semaphore.tryAcquire(180, TimeUnit.SECONDS)) {
                    log.warn("[Dispatcher] Semaphore timeout for agent={}", node.agentName);
                    node.status = TaskStatus.FAILED;
                    streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                            "agentName", node.agentName, "status", "error", "error", "等待槽位超时"
                    ));
                    if (customOnComplete != null) customOnComplete.run();
                    else handleNodeCompletion(node, conversationId, semaphore);
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                node.status = TaskStatus.FAILED;
                if (customOnComplete != null) customOnComplete.run();
                else handleNodeCompletion(node, conversationId, semaphore);
                return;
            }

            activeThreads.put(conversationId + ":" + node.agentName, Thread.currentThread());
            try {
                executeSingleNode(node, conversationId, () -> {
                    if (customOnComplete != null) customOnComplete.run();
                    else handleNodeCompletion(node, conversationId, semaphore);
                });
            } finally {
                activeThreads.remove(conversationId + ":" + node.agentName);
                semaphore.release();
            }
        });
    }
```

- [ ] **Step 4: Change handleNodeCompletion COMPLETED branch — READY instead of auto-schedule**

In `handleNodeCompletion` (around line 256), replace:
```java
            if (node.status == TaskStatus.COMPLETED) {
                // Decrement inDegree of dependents; schedule if ready
                for (TaskNode dependent : node.dependents) {
                    dependent.inDegree--;
                    if (dependent.inDegree == 0 && dependent.status == TaskStatus.PENDING) {
                        scheduleNode(dependent, conversationId, semaphore);
                    }
                }
            }
```
with:
```java
            if (node.status == TaskStatus.COMPLETED) {
                // Decrement inDegree of dependents; mark READY instead of auto-scheduling
                for (TaskNode dependent : node.dependents) {
                    dependent.inDegree--;
                    if (dependent.inDegree == 0 && dependent.status == TaskStatus.PENDING) {
                        dependent.status = TaskStatus.READY;
                        streamTracker.broadcastObject(conversationId, "agent_ready", Map.of(
                                "agentName", dependent.agentName,
                                "agentId", String.valueOf(dependent.dagTask.agent.getId()),
                                "dependsOn", node.agentName,
                                "taskDescription", dependent.dagTask.task
                        ));
                        log.info("[Dispatcher] Agent {} marked READY, waiting for user confirmation", dependent.agentName);
                    }
                }
            }
```

- [ ] **Step 5: Add continueNode() public method**

Insert after `executeCollected`'s closing brace and before `scheduleReady`:
```java
    /**
     * Continue a READY node after user confirmation.
     * Idempotent: only acts on nodes with READY status.
     */
    public void continueNode(String conversationId, String agentName, Semaphore semaphore) {
        DagState state = activeDags.get(conversationId);
        if (state == null) {
            log.warn("[Dispatcher] continueNode: no active DAG for conversation={}", conversationId);
            return;
        }
        TaskNode node = state.nodeMap.get(agentName);
        if (node == null) {
            log.warn("[Dispatcher] continueNode: agent '{}' not found in DAG", agentName);
            return;
        }
        if (node.status != TaskStatus.READY) {
            log.info("[Dispatcher] continueNode: agent '{}' status={}, not READY, skipping", agentName, node.status);
            return;
        }
        Semaphore sem = semaphore != null ? semaphore : state.semaphore;
        scheduleNode(node, conversationId, sem);
    }
```

- [ ] **Step 6: Add spawnSingleAgent() for @mentions during DAG pause**

Insert `spawnSingleAgent` after `continueNode`:
```java
    /**
     * Directly spawn a single agent without DAG collection.
     * Used during DAG pause for ad-hoc @mentions to completed agents.
     *
     * @return true if the line was an @AgentName dispatch
     */
    public boolean spawnSingleAgent(Long conversationDbId, String conversationId,
                                     Map<String, AgentEntity> agentNameMap, String line,
                                     Semaphore semaphore) {
        Matcher m = AGENT_PATTERN.matcher(line.trim());
        if (!m.matches()) return false;

        String agentName = m.group(1);
        String task = m.group(3) != null ? m.group(3).trim() : "";

        AgentEntity agent = agentNameMap.get(agentName);
        if (agent == null) {
            log.warn("[Dispatcher] spawnSingle: agent '{}' not found", agentName);
            return false;
        }

        String claudeMdPath = groupConversationService.getClaudeMdPath(conversationDbId, agentName);

        streamTracker.incrementFlux(conversationId);

        DagTask dagTask = new DagTask(agentName, agent, task, null, claudeMdPath);
        TaskNode node = new TaskNode(agentName, dagTask);

        streamTracker.broadcastObject(conversationId, "agent_message_start", Map.of(
                "agentName", agentName,
                "agentId", String.valueOf(agent.getId()),
                "taskDescription", task
        ));

        scheduleNode(node, conversationId, semaphore, () -> {
            ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
            if (cr.allDone()) {
                streamTracker.broadcastObject(conversationId, "done", Map.of(
                        "conversationId", conversationId,
                        "status", "completed"
                ));
            }
        });

        return true;
    }
```

- [ ] **Step 7: Update resetForTurn to clear activeDags**

In `resetForTurn` (after `collectedTasks.remove(conversationId)`), add:
```java
        activeDags.remove(conversationId);
```

- [ ] **Step 8: Build verification**

```bash
cd D:/code/Loom/mateclaw-dev && mvn compile -pl mateclaw-domain -q
```

- [ ] **Step 9: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java
git commit -m "feat: add READY status, continueNode, spawnSingleAgent, activeDags for DAG pause-resume"
```

---

### Task 2: ChatController — /dag/continue endpoint + DAG-aware @mention routing

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/channel/web/ChatController.java`

- [ ] **Step 1: Add continueDag endpoint**

Find a suitable place in ChatController (after existing endpoint methods, before the class closing brace). Add:
```java
    @PostMapping("/{conversationId}/dag/continue")
    public R<Void> continueDag(@PathVariable String conversationId,
                               @RequestBody Map<String, String> body) {
        String agentName = body.get("agentName");
        if (agentName == null || agentName.isBlank()) {
            return R.fail("agentName is required");
        }
        // Resolve semaphore from group config
        Semaphore semaphore = null;
        try {
            var conv = conversationService.getByConversationId(conversationId);
            if (conv != null) {
                Integer maxParallel = groupConversationMapper != null
                        ? groupConversationMapper.selectOne(
                            new LambdaQueryWrapper<vip.mate.domain.group.model.GroupConversationEntity>()
                                .eq(vip.mate.domain.group.model.GroupConversationEntity::getConversationId, conv.getId()))
                                .getMaxParallelTasks()
                        : null;
                semaphore = new Semaphore(maxParallel != null && maxParallel > 0 ? maxParallel : 4);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve semaphore for continueDag: {}", e.getMessage());
            semaphore = new Semaphore(4);
        }
        mentionDispatcher.continueNode(conversationId, agentName, semaphore);
        return R.ok();
    }
```

Wait — the GroupConversationMapper is not injected into ChatController. Let me simplify: use a default semaphore and pass null to let continueNode use the cached one from activeDags.

```java
    @PostMapping("/{conversationId}/dag/continue")
    public R<Void> continueDag(@PathVariable String conversationId,
                               @RequestBody Map<String, String> body) {
        String agentName = body.get("agentName");
        if (agentName == null || agentName.isBlank()) {
            return R.fail("agentName is required");
        }
        mentionDispatcher.continueNode(conversationId, agentName, null);
        return R.ok();
    }
```

Simpler, and continueNode already falls back to the cached semaphore.

- [ ] **Step 2: In group chat flow, check for active DAG before orchestrator invocation**

In the group chat branch (around line 672), before calling `groupOrchestratorService.buildOrchestratorPrompt()`, check if the user message is a single @mention to a member agent while a DAG is active:

The approach: In the group chat section, after `isGroupChat` is determined to be true, check if there's an active DAG and the first line of the message starts with @. If so, route to `spawnSingleAgent` instead of triggering the orchestrator.

Read the ChatController around lines 672-678 to see the exact context, then add a check:

```java
                if (isGroupChat && agentNameMap != null && convDbId != null) {
                    // Check if DAG is paused and message is @mention to a member
                    String trimmed = message.trim();
                    if (mentionDispatcher.hasActiveDag(convId) && trimmed.startsWith("@")) {
                        String firstLine = trimmed.split("\\n")[0];
                        mentionDispatcher.spawnSingleAgent(convDbId, convId, agentNameMap, firstLine, groupSemaphore);
                        // Still stream the user message as normal, but skip orchestrator
                        agentFlux = Flux.just(new AgentService.StreamDelta("", null));
                    } else {
                        // Normal group chat flow
                        ...
                    }
```

This is getting complex. Let me simplify: add a `hasActiveDag(String conversationId)` method to AgentMentionDispatcher, and in ChatController, check it before the orchestrator path.

Actually, let me simplify further. The key check is: if user message is `@AgentName ...` AND DAG is active → spawnSingleAgent. Otherwise → normal orchestrator flow.

But this is an integration concern that needs care. Let me add a single method:

```java
// In AgentMentionDispatcher:
public boolean hasActiveDag(String conversationId) {
    return activeDags.containsKey(conversationId);
}
```

And in ChatController, before line 672 (where the group chat orchestrator path starts), add:

```java
                if (isGroupChat && agentNameMap != null && convDbId != null
                        && mentionDispatcher.hasActiveDag(convId)
                        && message.trim().startsWith("@")) {
                    // DAG paused: route @mention directly, skip orchestrator
                    mentionDispatcher.spawnSingleAgent(convDbId, convId, agentNameMap,
                            message.trim().split("\\n")[0], groupSemaphore);
                    agentFlux = Flux.just(new AgentService.StreamDelta(message, null));
                } else if (isGroupChat && agentNameMap != null && convDbId != null) {
```

So the old `if (isGroupChat && ...)` becomes `else if ...`.

Wait, but the original code has `if (isGroupChat && agentNameMap != null && convDbId != null) {` on line 672. After the change, it should be:
- First check DAG pause + @mention
- Then normal group chat
- Else direct chat

Let me make this a precise edit.

- [ ] **Step 3: Add hasActiveDag() to AgentMentionDispatcher**

In AgentMentionDispatcher, add after `continueNode`:
```java
    public boolean hasActiveDag(String conversationId) {
        return activeDags.containsKey(conversationId);
    }
```

- [ ] **Step 4: Modify ChatController group chat branch**

Read ChatController.java around line 672. Find:
```java
                if (isGroupChat && agentNameMap != null && convDbId != null) {
                    // ── Group chat: use arther-agent Agent01 as Orchestrator ──
```

Replace with:
```java
                if (isGroupChat && agentNameMap != null && convDbId != null
                        && mentionDispatcher.hasActiveDag(convId)
                        && message.trim().startsWith("@")) {
                    // ── DAG paused: route @mention directly, skip orchestrator ──
                    mentionDispatcher.spawnSingleAgent(convDbId, convId, agentNameMap,
                            message.trim(), groupSemaphore);
                    agentFlux = Flux.just(new AgentService.StreamDelta(message, null));
                } else if (isGroupChat && agentNameMap != null && convDbId != null) {
                    // ── Group chat: use arther-agent Agent01 as Orchestrator ──
```

Note: this changes the `if` to `else if` for the normal group chat path. Make sure the `} else {` that leads to the direct chat path still works.

- [ ] **Step 5: Add imports for Map in ChatController**

Check if `java.util.Map` is imported in ChatController. If not, add:
```java
import java.util.Map;
```

- [ ] **Step 6: Build full project**

```bash
cd D:/code/Loom/mateclaw-dev && mvn compile -Dmaven.test.skip=true -q
```

- [ ] **Step 7: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/channel/web/ChatController.java
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java
git commit -m "feat: add /dag/continue endpoint and DAG-pause @mention routing in ChatController"
```

---

### Task 3: Frontend — agent_ready event + READY UI + continue API

**Files:**
- Modify: `AIagent_frontend/src/stores/chat.js`
- Modify: `AIagent_frontend/src/components/chat/MessageBubble.vue`
- Modify: `AIagent_frontend/src/views/ChatView.vue`
- Modify: `AIagent_frontend/src/api/chat.js`

- [ ] **Step 1: chat.js — handle agent_ready SSE event**

Read `chat.js`. Find the `agent_message_complete` handler (around line 285). After it, add:
```js
    sse.on('agent_ready', (data) => {
      const agentId = agentStreams.value.get(data.agentName)
      if (agentId) {
        updateMessage(agentId, {
          status: 'ready',
          dependsOn: data.dependsOn || '',
          taskDescription: data.taskDescription || ''
        })
      }
    })
```

- [ ] **Step 2: chat.js — export continueDag API**

Read `AIagent_frontend/src/api/chat.js`. Add:
```js
export function continueDag(conversationId, agentName) {
  return apiClient.post(`/api/v1/chat/${conversationId}/dag/continue`, { agentName })
}
```

- [ ] **Step 3: MessageBubble.vue — READY status UI**

Read `MessageBubble.vue`. 

Edit A — Add `isReady` computed after `isWaiting`:
```js
const isReady = computed(() => props.message.status === 'ready')
```

Edit B — Add READY display after the waiting status div:
```html
      <div v-else-if="isReady" class="msg-status ready">
        就绪，等待确认
        <NButton size="tiny" type="primary" style="margin-left:8px"
          @click="emit('continue-dag', message)">
          继续执行
        </NButton>
      </div>
```

Edit C — Add CSS:
```css
.msg-status.ready {
  color: #1E8E3E;
}
```

- [ ] **Step 4: ChatView.vue — handle continue-dag event**

Read ChatView.vue.

Edit A — Import the API:
```js
import { continueDag } from '@/api/chat'
```

Edit B — Add handler function (after handleCancelTask):
```js
async function handleContinueDag(message) {
  const convId = chatStore.conversationId
  const agentName = message.senderAgentName
  if (!convId || !agentName) return
  try {
    await continueDag(convId, agentName)
  } catch (e) {
    console.error('Failed to continue DAG:', e)
  }
}
```

Edit C — Wire up in template. Find `@cancel-task="handleCancelTask"`. Add after:
```
        @continue-dag="handleContinueDag"
```

- [ ] **Step 5: Build frontend**

```bash
cd D:/code/Loom/AIagent_frontend && npm run build 2>&1 | tail -5
```

Expected: `✓ built in XXs`

- [ ] **Step 6: Commit**

```bash
cd D:/code/Loom
git add AIagent_frontend/src/stores/chat.js
git add AIagent_frontend/src/components/chat/MessageBubble.vue
git add AIagent_frontend/src/views/ChatView.vue
git add AIagent_frontend/src/api/chat.js
git commit -m "feat: add agent_ready SSE handling and continue button for DAG pause-resume"
```

---

## Spec Coverage Checklist

| Spec Requirement | Task |
|------------------|------|
| READY 状态 + 不自动调度下游 | Task 1 (Steps 1, 4) |
| agent_ready SSE 事件 | Task 1 (Step 4) |
| activeDags 缓存跨请求保持 | Task 1 (Steps 1, 2, 7) |
| continueNode() 公共方法 | Task 1 (Step 5) |
| scheduleNode customOnComplete 重载 | Task 1 (Step 3) |
| spawnSingleAgent for DAG 暂停 @指名 | Task 1 (Step 6) |
| hasActiveDag() | Task 2 (Step 3) |
| POST /dag/continue 端点 | Task 2 (Step 1) |
| DAG 暂停时 @指名路由 | Task 2 (Step 4) |
| resetForTurn 清理 activeDags | Task 1 (Step 7) |
| 前端 agent_ready 事件 | Task 3 (Step 1) |
| 前端 READY UI + 继续按钮 | Task 3 (Step 3) |
| 前端 API 调用 | Task 3 (Steps 2, 4) |
