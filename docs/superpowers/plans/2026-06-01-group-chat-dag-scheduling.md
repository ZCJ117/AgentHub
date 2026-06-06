# Group Chat DAG 依赖调度 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 AgentMentionDispatcher 内部新增轻量 DAG 编排器，解析 Agent01 的 `[after:AgentName]` 标记，按依赖关系异步回调驱动子 Agent 串/并行执行。

**Architecture:** 将 AgentMentionDispatcher 从"逐行立即派发"改为"收集→构建 DAG→拓扑回调驱动"三阶段。Agent01 通过在 `@行` 中附加 `[after:XXX]` 声明依赖，调度器解析后构建 TaskNode 依赖图，入度为 0 的节点立即执行，完成后回调递减下游入度并触发就绪调度。上游失败时下游标记 WAITING。

**Tech Stack:** Java 21 Virtual Threads + Reactor Flux + ConcurrentHashMap + Semaphore

**Spec:** `docs/superpowers/specs/2026-06-01-group-chat-dag-scheduling-design.md`

---

## File Map

| File | Role | Action |
|------|------|--------|
| `agents.yml` | Agent01 instruction — 输出格式规则 | Modify (~8 lines) |
| `AgentMentionDispatcher.java` | DAG 数据结构 + collect/build/schedule 三阶段 | Major refactor |
| `ChatController.java` | 调用侧从 dispatchIfComplete 切换到 collectLine + executeCollected | Modify (~10 lines) |

---

### Task 1: Update agents.yml — Agent01 instruction 增加 `[after:AgentName]` 标记规则

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/resources/agent/agents.yml:60-68`

- [ ] **Step 1: Replace the existing @ 指派格式规则**

Replace lines 60-68 (from "每条任务指派独占一行" through "如果任务有明确的先后依赖"):

```yaml
                  每条任务指派独占一行，格式严格为：@Agent名称 [after:依赖Agent名称] 任务描述

                  每个智能体在一轮输出中只能被 @ 指派一次。

                  [after:Agent名称] 标记仅在该子任务必须等待另一个Agent完成后才能开始时使用。无依赖则省略整个 [...] 标记。一个任务最多依赖一个上游Agent。

                  示例 —— 前端和后端都依赖设计先完成：
                    @设计Agent 设计首页UI布局
                    @前端Agent [after:设计Agent] 根据设计稿实现前端页面
                    @后端Agent [after:设计Agent] 根据设计稿实现后端API接口
                  设计Agent先执行，完成后前端Agent和后端Agent并行执行。

                  如果任务没有依赖关系，不使用 [after:] 标记，所有Agent可并行执行：
                    @前端Agent 实现首页UI
                    @后端Agent 实现首页API

                  在任务描述中引用其他智能体时，去掉 @ 前缀使用名称。
```

- [ ] **Step 2: Verify YAML syntax is valid**

Run: `cd mateclaw-dev/mateclaw-server && python -c "import yaml; yaml.safe_load(open('src/main/resources/agent/agents.yml'))"`

Expected: No output (success). If error, fix YAML indentation.

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/resources/agent/agents.yml
git commit -m "feat: add [after:AgentName] dependency syntax to Agent01 orchestrator instruction"
```

---

### Task 2: Refactor AgentMentionDispatcher — add DAG data structures and regex

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java`

- [ ] **Step 1: Add inner types after the class fields (after line 47)**

Replace the `AGENT_PATTERN` field and `dispatchedAgents`/`activeThreads` fields, then add new types:

```java
    /** Regex: @AgentName [after:DependencyAgent] task content */
    private static final Pattern AGENT_PATTERN = Pattern.compile("^@(\\S+)\\s+(?:\\[after:(\\S+)\\]\\s+)?(.+)$");

    /** Track active agent streams per conversation to avoid duplicate spawns */
    private final Map<String, Map<String, Boolean>> dispatchedAgents = new ConcurrentHashMap<>();

    /** Track active virtual threads so they can be interrupted on abort */
    private final Map<String, Thread> activeThreads = new ConcurrentHashMap<>();

    /** Collected DAG tasks for the current turn */
    private final Map<String, List<DagTask>> collectedTasks = new ConcurrentHashMap<>();

    enum TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, WAITING }

    record DagTask(String agentName, AgentEntity agent, String task,
                   String dependsOnAgentName, String claudeMdPath) {}

    static class TaskNode {
        final String agentName;
        final DagTask dagTask;
        volatile int inDegree;
        volatile TaskStatus status = TaskStatus.PENDING;
        final List<TaskNode> dependents = new ArrayList<>();

        TaskNode(String agentName, DagTask dagTask) {
            this.agentName = agentName;
            this.dagTask = dagTask;
        }
    }
```

- [ ] **Step 2: Build verification**

Run: `cd mateclaw-dev && mvn compile -pl mateclaw-domain -q`

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java
git commit -m "feat: add DAG data structures (DagTask, TaskNode, TaskStatus) to AgentMentionDispatcher"
```

---

### Task 3: Add collectLine method — replace dispatchIfComplete

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java`

- [ ] **Step 1: Replace the dispatchIfComplete method with collectLine**

Replace the entire `dispatchIfComplete` method (lines 63-124) with:

```java
    /**
     * Collect a line from the orchestrator output. If it matches @AgentName pattern,
     * parse and store the DagTask for later DAG-based execution.
     *
     * @return true if the line was an @AgentName dispatch
     */
    public boolean collectLine(Long conversationDbId, String conversationId,
                               Map<String, AgentEntity> agentNameMap, String line) {
        Matcher m = AGENT_PATTERN.matcher(line.trim());
        if (!m.matches()) return false;

        String agentName = m.group(1);
        String afterAgent = m.group(2);  // null if no [after:XXX]
        String task = m.group(3).trim();

        // Dedup: don't collect the same agent twice in one turn
        Map<String, Boolean> convDispatched = dispatchedAgents.computeIfAbsent(
                conversationId, k -> new ConcurrentHashMap<>());
        if (convDispatched.putIfAbsent(agentName, Boolean.TRUE) != null) {
            log.info("[Dispatcher] Agent {} already collected in this turn, skipping", agentName);
            return true;
        }

        AgentEntity agent = agentNameMap.get(agentName);
        if (agent == null) {
            log.warn("[Dispatcher] @Agent name '{}' not found in group members", agentName);
            return true;
        }

        String claudeMdPath = groupConversationService.getClaudeMdPath(conversationDbId, agentName);

        collectedTasks.computeIfAbsent(conversationId, k -> new ArrayList<>())
                .add(new DagTask(agentName, agent, task, afterAgent, claudeMdPath));

        log.info("[Dispatcher] Collected task: agent={}, dependsOn={}, task={}",
                agentName, afterAgent != null ? afterAgent : "none",
                task.length() > 80 ? task.substring(0, 80) + "..." : task);
        return true;
    }
```

- [ ] **Step 2: Build verification**

Run: `cd mateclaw-dev && mvn compile -pl mateclaw-domain -q`

Expected: BUILD SUCCESS (dispatchIfComplete removed, ChatController will fail — that's handled in Task 6)

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java
git commit -m "feat: replace dispatchIfComplete with collectLine for DAG collection phase"
```

---

### Task 4: Add executeSingleNode — refactored agent execution with callback

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java`

- [ ] **Step 1: Add executeSingleNode method (replaces spawnAndStreamAgent)**

Replace the `spawnAndStreamAgent` method (lines 126-217) with the following. The key change: accepts a `Runnable onComplete` callback instead of directly calling `completeAndBroadcastDoneIfLast`:

```java
    private void executeSingleNode(TaskNode node, String conversationId, Runnable onComplete) {
        AgentEntity agent = node.dagTask.agent;
        String agentName = node.agentName;
        String task = node.dagTask.task;
        String claudeMdPath = node.dagTask.claudeMdPath;
        String agentIdStr = String.valueOf(agent.getId());
        StringBuilder fullResponse = new StringBuilder();

        try {
            String workingDir = resolveWorkingDir(conversationId);
            boolean spawned = processManager.spawn(
                    agentIdStr, agent.getCliType(), agentName,
                    agent.getSystemPrompt(), claudeMdPath, workingDir);

            if (!spawned && !processManager.isRunning(agentIdStr)) {
                log.error("[Dispatcher] Failed to spawn CLI for agent={}", agentName);
                node.status = TaskStatus.FAILED;
                streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                        "agentName", agentName,
                        "status", "failed",
                        "error", "Agent CLI 启动失败"
                ));
                onComplete.run();
                return;
            }

            Flux.<AgentService.StreamDelta>create(sink -> {
                processManager.registerResponseSink(agentIdStr, sink);
                sink.onDispose(() -> {
                    processManager.unregisterResponseSink(agentIdStr);
                    processManager.terminate(agentIdStr);
                });

                java.util.LinkedHashMap<String, Object> chatPayload = new java.util.LinkedHashMap<>();
                chatPayload.put("message", task);
                chatPayload.put("conversationId", conversationId);
                chatPayload.put("systemPrompt", agent.getSystemPrompt() != null ? agent.getSystemPrompt() : "");
                BridgeFrame request = BridgeFrame.of("chat_request", chatPayload);
                processManager.sendFrame(agentIdStr, request);
            }, FluxSink.OverflowStrategy.BUFFER)
            .publishOn(Schedulers.boundedElastic())
            .doOnNext(delta -> {
                if (delta.content() != null) {
                    fullResponse.append(delta.content());
                    streamTracker.broadcastObject(conversationId, "content_delta", Map.of(
                            "delta", delta.content(),
                            "agentName", agentName
                    ));
                }
            })
            .doOnComplete(() -> {
                String responseText = fullResponse.toString();
                if (!responseText.isBlank()) {
                    try {
                        var msg = conversationService.saveMessage(conversationId, "assistant", responseText);
                        msg.setSenderAgentId(agent.getId());
                        try {
                            messageMapper.updateById(msg);
                        } catch (Exception e) {
                            log.error("[Dispatcher] Failed to set senderAgentId on msg {}: {}", msg.getId(), e.getMessage());
                        }
                    } catch (Exception e) {
                        log.error("[Dispatcher] Failed to save message for agent={}: {}", agentName, e.getMessage());
                    }
                }
                processManager.terminate(agentIdStr);
                node.status = TaskStatus.COMPLETED;
                streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                        "agentName", agentName,
                        "status", "completed"
                ));
                log.info("[Dispatcher] Agent {} completed, response length={}", agentName, responseText.length());
                onComplete.run();
            })
            .doOnError(err -> {
                log.error("[Dispatcher] Agent {} error: {}", agentName, err.getMessage());
                processManager.terminate(agentIdStr);
                node.status = TaskStatus.FAILED;
                streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                        "agentName", agentName,
                        "status", "failed",
                        "error", err.getMessage()
                ));
                onComplete.run();
            })
            .subscribe();

        } catch (Exception e) {
            log.error("[Dispatcher] Failed to spawn agent {}: {}", agentName, e.getMessage());
            node.status = TaskStatus.FAILED;
            streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                    "agentName", agentName,
                    "status", "failed",
                    "error", e.getMessage()
            ));
            onComplete.run();
        }
    }
```

- [ ] **Step 2: Remove the now-unused private helper methods**

Delete `broadcastAgentError()` and `completeAndBroadcastDoneIfLast()` — their logic is now inlined in `executeSingleNode` and the new `handleNodeCompletion`.

- [ ] **Step 3: Build verification**

Run: `cd mateclaw-dev && mvn compile -pl mateclaw-domain -q`

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java
git commit -m "feat: extract executeSingleNode with onComplete callback for DAG-driven execution"
```

---

### Task 5: Add DAG build, schedule, and completion methods

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java`

- [ ] **Step 1: Add the three new methods (executeCollected, scheduleNode, handleNodeCompletion)**

Insert these methods after `collectLine` (before `executeSingleNode`):

```java
    /**
     * Build the DAG from collected tasks and start scheduling.
     * Called once after the orchestrator stream completes.
     */
    public void executeCollected(String conversationId, Semaphore semaphore) {
        List<DagTask> tasks = collectedTasks.remove(conversationId);
        if (tasks == null || tasks.isEmpty()) return;

        int total = tasks.size();

        // Pre-increment flux count for all tasks so "done" doesn't fire early
        for (int i = 0; i < total; i++) {
            streamTracker.incrementFlux(conversationId);
        }

        // Build node map: agentName -> TaskNode
        Map<String, TaskNode> nodeMap = new LinkedHashMap<>();
        for (DagTask dt : tasks) {
            nodeMap.put(dt.agentName, new TaskNode(dt.agentName, dt));
        }

        // Build dependency edges
        for (TaskNode node : nodeMap.values()) {
            String dependsOn = node.dagTask.dependsOnAgentName;
            if (dependsOn == null) continue;
            TaskNode upstream = nodeMap.get(dependsOn);
            if (upstream == null) {
                log.warn("[Dispatcher] Dependency '{}' not found for '{}', treating as independent",
                        dependsOn, node.agentName);
                continue;
            }
            node.inDegree++;
            upstream.dependents.add(node);
        }

        // Cycle detection: if every node has inDegree > 0, there's a cycle
        boolean hasReady = nodeMap.values().stream().anyMatch(n -> n.inDegree == 0);
        if (!hasReady) {
            log.error("[Dispatcher] Circular dependency detected in group chat plan");
            // Degrade: treat all as independent
            nodeMap.values().forEach(n -> { n.inDegree = 0; n.dependents.clear(); });
        }

        // Broadcast start events for all tasks
        for (TaskNode node : nodeMap.values()) {
            Map<String, Object> startEvent = new LinkedHashMap<>();
            startEvent.put("agentName", node.agentName);
            startEvent.put("agentId", String.valueOf(node.dagTask.agent.getId()));
            startEvent.put("taskDescription", node.dagTask.task);
            if (node.dagTask.dependsOnAgentName != null) {
                startEvent.put("dependsOn", node.dagTask.dependsOnAgentName);
            }
            streamTracker.broadcastObject(conversationId, "agent_message_start", startEvent);
        }

        log.info("[Dispatcher] DAG built: {} nodes, ready={}",
                nodeMap.size(), nodeMap.values().stream().filter(n -> n.inDegree == 0).count());

        // Schedule nodes with no dependencies
        scheduleReady(new ArrayList<>(nodeMap.values()), conversationId, semaphore);
    }

    private void scheduleReady(List<TaskNode> allNodes, String conversationId, Semaphore semaphore) {
        synchronized (scheduleLock) {
            for (TaskNode node : allNodes) {
                if (node.status != TaskStatus.PENDING || node.inDegree > 0) continue;
                scheduleNode(node, conversationId, semaphore);
            }
        }
    }

    private void scheduleNode(TaskNode node, String conversationId, Semaphore semaphore) {
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
                            "agentName", node.agentName,
                            "status", "failed",
                            "error", "等待槽位超时"
                    ));
                    handleNodeCompletion(node, conversationId, semaphore);
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                node.status = TaskStatus.FAILED;
                handleNodeCompletion(node, conversationId, semaphore);
                return;
            }

            try {
                activeThreads.put(conversationId + ":" + node.agentName, Thread.currentThread());
                executeSingleNode(node, conversationId, () ->
                        handleNodeCompletion(node, conversationId, semaphore));
            } finally {
                activeThreads.remove(conversationId + ":" + node.agentName);
                semaphore.release();
            }
        });
        activeThreads.put(conversationId + ":" + node.agentName, vt);
    }

    private void handleNodeCompletion(TaskNode node, String conversationId, Semaphore semaphore) {
        synchronized (scheduleLock) {
            if (node.status == TaskStatus.COMPLETED) {
                // Decrement inDegree of dependents; schedule if ready
                for (TaskNode dependent : node.dependents) {
                    dependent.inDegree--;
                    if (dependent.inDegree == 0 && dependent.status == TaskStatus.PENDING) {
                        scheduleNode(dependent, conversationId, semaphore);
                    }
                }
            } else {
                // Upstream failed: mark all dependents as WAITING
                for (TaskNode dependent : node.dependents) {
                    if (dependent.status == TaskStatus.PENDING) {
                        dependent.status = TaskStatus.WAITING;
                        streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                                "agentName", dependent.agentName,
                                "status", "waiting",
                                "dependsOn", node.agentName
                        ));
                        // Decrement flux counter for the waiting node (will never execute)
                        ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                        if (cr.allDone()) {
                            streamTracker.broadcastObject(conversationId, "done", Map.of(
                                    "conversationId", conversationId,
                                    "status", "completed"
                            ));
                        }
                    }
                }
            }

            // Decrement flux counter for the completed/failed node
            ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
            if (cr.allDone()) {
                streamTracker.broadcastObject(conversationId, "done", Map.of(
                        "conversationId", conversationId,
                        "status", "completed"
                ));
            }
        }
    }
```

- [ ] **Step 2: Add scheduleLock field**

Add after `activeThreads` field (after line 50):

```java
    /** Lock for DAG scheduling to prevent concurrent modification of TaskNode state */
    private final Object scheduleLock = new Object();
```

- [ ] **Step 3: Update resetForTurn to clear collectedTasks**

Modify `resetForTurn` method (lines 253-263) to add collectedTasks cleanup:

```java
    /** Reset dispatch tracking for a new turn */
    public void resetForTurn(String conversationId) {
        dispatchedAgents.remove(conversationId);
        collectedTasks.remove(conversationId);
        // Interrupt any active dispatch threads for this conversation
        String prefix = conversationId + ":";
        activeThreads.forEach((key, thread) -> {
            if (key.startsWith(prefix)) {
                thread.interrupt();
            }
        });
        activeThreads.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
    }
```

- [ ] **Step 4: Build verification**

Run: `cd mateclaw-dev && mvn compile -pl mateclaw-domain -q`

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java
git commit -m "feat: add DAG build, schedule, and callback-driven completion to AgentMentionDispatcher"
```

---

### Task 6: Update ChatController — switch group chat to collect+execute flow

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/channel/web/ChatController.java:694-719`

- [ ] **Step 1: Replace dispatchIfComplete calls with collectLine + executeCollected**

Replace lines 694-719:

```java
                    mentionDispatcher.resetForTurn(convId);

                    agentFlux = groupOrchestratorService.callOrchestrator(username, orchPrompt, history)
                            .map(text -> {
                                // Feed text through line buffer for @AgentName collection
                                for (int i = 0; i < text.length(); i++) {
                                    char c = text.charAt(i);
                                    if (c == '\n') {
                                        String line = lineBuffer.toString();
                                        lineBuffer.setLength(0);
                                        mentionDispatcher.collectLine(convDbId, convId,
                                                agentNameMap, line);
                                    } else {
                                        lineBuffer.append(c);
                                    }
                                }
                                // Return content as StreamDelta for broadcast
                                return new AgentService.StreamDelta(text, null);
                            })
                            .doOnComplete(() -> {
                                // Flush remaining buffer and execute DAG
                                if (lineBuffer.length() > 0) {
                                    mentionDispatcher.collectLine(convDbId, convId,
                                            agentNameMap, lineBuffer.toString());
                                }
                                mentionDispatcher.executeCollected(convId, groupSemaphore);
                            })
                            .doOnError(err -> {
                                log.error("arther-agent orchestrator stream failed for group {}: {}",
                                        convId, err.getMessage());
                                broadcastEvent(convId, "error", Map.of(
                                        "message", "Orchestrator 服务不可用: " + err.getMessage()
                                ));
                            });
```

- [ ] **Step 2: Build full project**

Run: `cd mateclaw-dev && mvn compile -q`

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/channel/web/ChatController.java
git commit -m "feat: switch group chat to collect-then-DAG-execute flow in ChatController"
```

---

### Task 7: End-to-end smoke test (manual)

- [ ] **Step 1: Start the backend**

Run: `cd mateclaw-dev/mateclaw-server && mvn spring-boot:run`

Expected: Server starts on port 18088 without errors

- [ ] **Step 2: Send a group chat message with dependency**

Using curl or the frontend, send a group chat message that should trigger Agent01 to output tasks with `[after:XXX]` dependencies. Verify:

1. Agent01 outputs `@AgentName [after:OtherAgent] task` format
2. collectLine parses the dependency correctly (check logs)
3. DAG is built and ready nodes start first
4. Downstream nodes wait until upstream completes
5. If upstream fails, downstream shows "waiting" status

- [ ] **Step 3: Stop the backend**

---

## Spec Coverage Checklist

| Spec Requirement | Task |
|------------------|------|
| Agent01 输出 `[after:AgentName]` 标记 | Task 1 |
| DAG 构建（解析依赖 → TaskNode 图 → 入度计算） | Task 2 + Task 5 |
| 循环依赖检测 | Task 5 (hasReady check) |
| 入度为 0 的节点立即执行 | Task 5 (scheduleReady) |
| 完成回调递减下游入度并触发调度 | Task 5 (handleNodeCompletion) |
| 上游失败 → 下游 WAITING | Task 5 (handleNodeCompletion else branch) |
| Semaphore 保留并发控制 | Task 5 (scheduleNode, semaphore.tryAcquire) |
| SSE 事件广播保持不变 | Task 4 + Task 5 |
| 重置时清理收集列表 | Task 5 (resetForTurn update) |
