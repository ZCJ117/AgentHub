# Group Chat Aggregation Report — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After all sub-agents in a group chat DAG reach terminal state, call Agent01 back to generate a natural-language summary of results, failures, and next steps.

**Architecture:** Add aggregation callback inside `AgentMentionDispatcher.handleNodeCompletion()` where DAG completion is already detected. Delegate prompt building to `GroupOrchestratorService`. The summary streams via existing `content_delta` SSE events (no new event types). No frontend changes.

**Tech Stack:** Java 21, Spring Boot 3.5, Reactor Flux, existing SSE broadcast infrastructure

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `GroupOrchestratorService.java` | Modify | Add `buildAggregationPrompt()` method |
| `AgentMentionDispatcher.java` | Modify | Add `aggregateAndReport()`; wire into `handleNodeCompletion`; extend `DagState`; inject `GroupOrchestratorService` |
| `ChatController.java` | Modify | Pass `originalTaskMessage` + `username` through `executeCollected` |

---

### Task 1: Add `buildAggregationPrompt()` to GroupOrchestratorService

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/GroupOrchestratorService.java`

- [ ] **Step 1: Add the `buildAggregationPrompt()` method**

Add after the existing `buildOrchestratorPrompt()` method (around line 202):

```java
/**
 * Build the prompt for Agent01 to aggregate sub-agent execution results
 * and generate a summary report for the user.
 */
public String buildAggregationPrompt(
        String originalTask,
        List<Map<String, Object>> agentResults) {

    var sb = new StringBuilder();
    sb.append("你是一个任务协调者。以下是本轮任务分配的执行结果汇总，请撰写一份简明的总结报告。\n\n");
    sb.append("## 原始任务\n").append(originalTask).append("\n\n");
    sb.append("## 子任务执行结果\n\n");

    for (var result : agentResults) {
        String agentName = (String) result.get("agentName");
        String status = (String) result.get("status");
        String task = (String) result.get("task");
        String output = (String) result.getOrDefault("output", "");
        String error = (String) result.getOrDefault("error", "");
        String waitingReason = (String) result.getOrDefault("waitingReason", "");

        switch (status) {
            case "completed" -> {
                sb.append("### ✅ ").append(agentName).append(" — 已完成\n");
                sb.append("**任务：** ").append(task).append("\n");
                sb.append("**输出：**\n");
                if (output.length() > 4000) {
                    sb.append(output, 0, 2000)
                      .append("\n\n...（省略 ").append(output.length() - 4000)
                      .append(" 字符）...\n\n")
                      .append(output, output.length() - 2000, output.length());
                } else {
                    sb.append(output);
                }
                sb.append("\n\n");
            }
            case "failed" -> {
                sb.append("### ❌ ").append(agentName).append(" — 执行失败\n");
                sb.append("**任务：** ").append(task).append("\n");
                sb.append("**错误：** ").append(error != null ? error : "未知错误").append("\n\n");
            }
            case "waiting" -> {
                sb.append("### ⏸ ").append(agentName).append(" — 等待中（上游失败）\n");
                sb.append("**任务：** ").append(task).append("\n");
                sb.append("**原因：** ").append(waitingReason != null ? waitingReason : "依赖的上游任务未完成").append("\n\n");
            }
        }
    }

    sb.append("---\n\n");
    sb.append("请总结：\n");
    sb.append("1. 本轮整体完成情况（成功数/失败数/等待数）\n");
    sb.append("2. 已完成任务的关键成果\n");
    sb.append("3. 失败任务的原因和建议下一步（如：可重新指派、可跳过等）\n");
    sb.append("4. 等待中的任务说明（因依赖未满足）");

    return sb.toString();
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd mateclaw-dev && mvn compile -pl mateclaw-domain -q
```

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/GroupOrchestratorService.java
git commit -m "feat: add buildAggregationPrompt to GroupOrchestratorService"
```

---

### Task 2: Extend executeCollected and DagState to carry aggregation context

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java`
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/channel/web/ChatController.java`

- [ ] **Step 1: Extend DagState record**

In `AgentMentionDispatcher.java`, line 68, add `originalTaskMessage` and `username` fields:

```java
record DagState(Map<String, TaskNode> nodeMap, Semaphore semaphore,
                String originalTaskMessage, String username) {}
```

- [ ] **Step 2: Change executeCollected signature**

Line 137, add parameters:

```java
public void executeCollected(String conversationId, Semaphore semaphore,
                             String originalTaskMessage, String username) {
```

- [ ] **Step 3: Update the activeDags.put call**

Line 212, update the constructor call:

```java
activeDags.put(conversationId, new DagState(nodeMap, semaphore, originalTaskMessage, username));
```

- [ ] **Step 4: Inject GroupOrchestratorService**

In `AgentMentionDispatcher.java`, add a new final field after existing fields (around line 48):

```java
private final GroupOrchestratorService groupOrchestratorService;
```

Note: `GroupOrchestratorService` is in the same package (`vip.mate.domain.group.service`), so no new import needed. The `@RequiredArgsConstructor` annotation auto-generates the constructor.

- [ ] **Step 5: Update ChatController call site**

In `ChatController.java`, line 726, update the `executeCollected` call to pass the two new arguments:

```java
mentionDispatcher.executeCollected(convId, groupSemaphore, message, username);
```

- [ ] **Step 6: Verify compilation**

```bash
cd mateclaw-dev && mvn compile -pl mateclaw-domain,mateclaw-server -q
```

- [ ] **Step 7: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java \
        mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/channel/web/ChatController.java
git commit -m "feat: wire aggregation context through executeCollected and DagState"
```

---

### Task 3: Implement aggregateAndReport() method

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java`

- [ ] **Step 1: Add the aggregateAndReport() method**

Insert after `handleNodeCompletion()` (before `buildContextMessage`, around line 421):

```java
/**
 * After all sub-agents reach terminal state, call Agent01 to generate
 * a natural-language summary of the DAG execution results.
 * Runs asynchronously in a virtual thread so it does not block the
 * semaphore release or the scheduleLock.
 */
private void aggregateAndReport(String conversationId, Map<String, TaskNode> nodeMap) {
    DagState state = activeDags.get(conversationId);
    if (state == null) {
        log.info("[Dispatcher] DAG already cleared for {}, skipping aggregation", conversationId);
        streamTracker.broadcastObject(conversationId, "done", Map.of(
                "conversationId", conversationId, "status", "completed"));
        return;
    }

    String originalTask = state.originalTaskMessage;
    String username = state.username;

    Thread.startVirtualThread(() -> {
        try {
            // 1. Collect sub-agent results from nodeMap
            List<Map<String, Object>> agentResults = new ArrayList<>();
            for (TaskNode node : nodeMap.values()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("agentName", node.agentName);
                result.put("task", node.dagTask.task);
                result.put("status", node.status.name().toLowerCase());

                if (node.status == TaskStatus.FAILED) {
                    result.put("error", "执行过程中发生错误");
                } else if (node.status == TaskStatus.WAITING) {
                    result.put("waitingReason",
                            "依赖的上游 Agent " + node.dagTask.dependsOnAgentName + " 执行失败");
                }

                // 2. Try to retrieve the sub-agent's saved message from DB
                if (node.dagTask.agent != null) {
                    try {
                        List<MessageEntity> recent = conversationService
                                .listRecentMessages(conversationId, 50);
                        if (recent != null) {
                            Long agentId = node.dagTask.agent.getId();
                            for (MessageEntity msg : recent) {
                                if (agentId.equals(msg.getSenderAgentId())
                                        && "assistant".equals(msg.getRole())) {
                                    String content = conversationService.renderMessageContent(msg);
                                    if (content != null && !content.isBlank()) {
                                        result.put("output", content);
                                    }
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[Dispatcher] Failed to retrieve output for {}: {}",
                                node.agentName, e.getMessage());
                    }
                }

                agentResults.add(result);
            }

            // 3. Build aggregation prompt
            String aggregationPrompt = groupOrchestratorService.buildAggregationPrompt(
                    originalTask, agentResults);

            // 4. Load conversation history for context
            List<Map<String, String>> history = List.of();
            try {
                List<MessageEntity> recent = conversationService.listRecentMessages(conversationId, 20);
                if (recent != null && !recent.isEmpty()) {
                    history = recent.stream()
                            .filter(msg -> !"system".equals(msg.getRole()))
                            .map(msg -> Map.of(
                                    "role", msg.getRole(),
                                    "content", conversationService.renderMessageContent(msg)))
                            .toList();
                }
            } catch (Exception e) {
                log.warn("[Dispatcher] Failed to load history for aggregation: {}", e.getMessage());
            }

            // 5. Call Agent01 and stream the summary
            StringBuilder summaryText = new StringBuilder();

            groupOrchestratorService.callOrchestrator(username, aggregationPrompt, history)
                    .doOnNext(text -> {
                        summaryText.append(text);
                        streamTracker.broadcastObject(conversationId, "content_delta", Map.of(
                                "delta", text,
                                "agentName", "Agent01",
                                "isAggregation", true
                        ));
                    })
                    .doOnComplete(() -> {
                        // Save summary as a message
                        if (!summaryText.isEmpty()) {
                            try {
                                var msg = conversationService.saveMessage(
                                        conversationId, "assistant", summaryText.toString());
                                // Look up Agent01 entity by name to set senderAgentId
                                try {
                                    var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentEntity>()
                                            .eq(AgentEntity::getName, "Agent01");
                                    AgentEntity agent01 = agentMapper.selectList(wrapper)
                                            .stream().findFirst().orElse(null);
                                    if (agent01 != null) {
                                        msg.setSenderAgentId(agent01.getId());
                                        messageMapper.updateById(msg);
                                    }
                                } catch (Exception e) {
                                    log.warn("[Dispatcher] Failed to set Agent01 sender: {}", e.getMessage());
                                }
                            } catch (Exception e) {
                                log.error("[Dispatcher] Failed to save aggregation message: {}", e.getMessage());
                            }
                        }

                        streamTracker.broadcastObject(conversationId, "done", Map.of(
                                "conversationId", conversationId,
                                "status", "completed"
                        ));
                        log.info("[Dispatcher] Aggregation completed for conversation={}, summaryLen={}",
                                conversationId, summaryText.length());
                    })
                    .doOnError(err -> {
                        log.error("[Dispatcher] Aggregation call failed for {}: {}",
                                conversationId, err.getMessage());

                        // Fallback: system-level summary
                        long completed = agentResults.stream()
                                .filter(r -> "completed".equals(r.get("status"))).count();
                        long failed = agentResults.stream()
                                .filter(r -> "failed".equals(r.get("status"))).count();
                        long waiting = agentResults.stream()
                                .filter(r -> "waiting".equals(r.get("status"))).count();

                        String fallback = "## 本轮任务汇总\n\n" +
                                "| 状态 | 数量 |\n" +
                                "|------|------|\n" +
                                "| ✅ 已完成 | " + completed + " |\n" +
                                "| ❌ 失败 | " + failed + " |\n" +
                                "| ⏸ 等待中 | " + waiting + " |\n";

                        try {
                            var msg = conversationService.saveMessage(
                                    conversationId, "system", fallback);
                            log.info("[Dispatcher] Saved fallback system summary msgId={}", msg.getId());
                        } catch (Exception e) {
                            log.error("[Dispatcher] Failed to save fallback summary: {}", e.getMessage());
                        }

                        // Stream the fallback as content_delta so frontend sees it
                        streamTracker.broadcastObject(conversationId, "content_delta", Map.of(
                                "delta", fallback,
                                "agentName", "Agent01",
                                "isAggregation", true
                        ));

                        streamTracker.broadcastObject(conversationId, "done", Map.of(
                                "conversationId", conversationId,
                                "status", "completed"
                        ));
                    })
                    .subscribe();
        } catch (Exception e) {
            log.error("[Dispatcher] Aggregation setup failed for {}: {}",
                    conversationId, e.getMessage());
            streamTracker.broadcastObject(conversationId, "done", Map.of(
                    "conversationId", conversationId, "status", "completed"));
        }
    });
}
```

- [ ] **Step 2: Add required imports**

Add these imports at the top of `AgentMentionDispatcher.java`:

```java
// Add to existing java.util imports (some may already exist):
import java.util.LinkedHashMap;

// Add reactor import if not already present:
import reactor.core.publisher.Flux;
```

Check if `Flux` is already imported — it is (line 6). `LinkedHashMap` may already be imported (used in `executeCollected`). Verify all needed types are imported.

- [ ] **Step 3: Verify compilation**

```bash
cd mateclaw-dev && mvn compile -pl mateclaw-domain -q
```

Expected: compilation succeeds without errors.

- [ ] **Step 4: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java
git commit -m "feat: add aggregateAndReport — Agent01 summarizes DAG results"
```

---

### Task 4: Wire aggregateAndReport into handleNodeCompletion

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java`

- [ ] **Step 1: Replace the allDone() branch in handleNodeCompletion**

In `AgentMentionDispatcher.java`, lines 413-419, replace:

```java
            // Decrement flux counter for the completed/failed node
            ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
            if (cr.allDone()) {
                streamTracker.broadcastObject(conversationId, "done", Map.of(
                        "conversationId", conversationId,
                        "status", "completed"
                ));
            }
```

With:

```java
            // Decrement flux counter for the completed/failed node
            ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
            if (cr.allDone()) {
                aggregateAndReport(conversationId, nodeMap);
            }
```

Note: `nodeMap` is not directly in scope inside `handleNodeCompletion`. The method receives a single `TaskNode node`. We need to get the full `nodeMap` from `activeDags`. Change to use `activeDags` lookup:

```java
            // Decrement flux counter for the completed/failed node
            ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
            if (cr.allDone()) {
                DagState state = activeDags.get(conversationId);
                if (state != null) {
                    aggregateAndReport(conversationId, state.nodeMap);
                } else {
                    streamTracker.broadcastObject(conversationId, "done", Map.of(
                            "conversationId", conversationId,
                            "status", "completed"
                    ));
                }
            }
```

- [ ] **Step 2: Verify compilation**

```bash
cd mateclaw-dev && mvn compile -pl mateclaw-domain -q
```

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java
git commit -m "feat: wire aggregateAndReport into DAG completion path"
```

---

### Task 5: Integration test — verify end-to-end aggregation flow

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/test/java/vip/mate/orchestrator/OrchestratorServiceTest.java`

- [ ] **Step 1: Add a test for aggregation prompt building**

Add to the existing test class:

```java
@Test
void buildAggregationPromptProducesCorrectStructure() {
    var service = new GroupOrchestratorService();

    List<Map<String, Object>> results = List.of(
        Map.of("agentName", "DesignBot", "status", "completed",
               "task", "设计首页", "output", "这是设计稿内容"),
        Map.of("agentName", "CodeBot", "status", "failed",
               "task", "实现代码", "error", "CLI 启动超时"),
        Map.of("agentName", "TestBot", "status", "waiting",
               "task", "编写测试", "waitingReason", "依赖的上游 Agent CodeBot 执行失败")
    );

    String prompt = service.buildAggregationPrompt("构建一个登录页", results);

    assertThat(prompt).contains("构建一个登录页");
    assertThat(prompt).contains("✅ DesignBot");
    assertThat(prompt).contains("❌ CodeBot");
    assertThat(prompt).contains("⏸ TestBot");
    assertThat(prompt).contains("CLI 启动超时");
    assertThat(prompt).contains("这是设计稿内容");
}
```

Note: `GroupOrchestratorService` has a no-arg constructor that loads from `agents.yml`. The test only calls `buildAggregationPrompt()` which is a pure string builder (no LLM call). If the constructor fails due to missing `agents.yml`, copy `agent/agents.yml` from main resources to `mateclaw-server/src/test/resources/agent/agents.yml`.

- [ ] **Step 2: Run the test**

```bash
cd mateclaw-dev && mvn test -pl mateclaw-server -Dtest=OrchestratorServiceTest#buildAggregationPromptProducesCorrectStructure -DfailIfNoTests=false
```

Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/test/java/vip/mate/orchestrator/OrchestratorServiceTest.java
git commit -m "test: add aggregation prompt structure test"
```

---

## Verification Checklist

After all tasks are complete:

1. `cd mateclaw-dev && mvn compile -q` — all modules compile
2. `cd mateclaw-dev && mvn test -q` — all tests pass, including new aggregation test
3. Manual smoke test flow:
   - Create a group chat with 2+ agents
   - Send a task that Agent01 can break into @mentions
   - Verify Agent01 streams its task decomposition
   - Verify sub-agents execute
   - After all sub-agents finish, verify Agent01 produces a summary message
   - Verify the summary includes success/failure/waiting counts and next-step guidance
   - Verify the `done` event still fires after the summary
