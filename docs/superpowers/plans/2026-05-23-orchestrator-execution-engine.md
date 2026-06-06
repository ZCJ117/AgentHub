# Orchestrator Execution Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the OrchestratorEngine component that drives multi-agent task execution with dependency-driven scheduling, parallel fan-out, timeout/retry/cancel, and SSE event broadcasting.

**Architecture:** Single `OrchestratorEngine` @Component using virtual threads + Semaphore for parallelism control, CompletableFuture for per-assignment execution, and a dependency graph for scheduling. Modifies `OrchestratorService` to async-trigger execution and `OrchestratorEventPublisher` to emit SSE events matching the API.txt protocol.

**Tech Stack:** Java 21, Spring Boot 3, MyBatis-Plus, JDK Virtual Threads, Project Reactor Flux

---

### Task 1: Create OrchestratorEngine.java

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\orchestrator\engine\OrchestratorEngine.java`

- [ ] **Step 1: Create the engine directory**

```bash
mkdir -p D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/java/vip/mate/orchestrator/engine
```

- [ ] **Step 2: Write OrchestratorEngine.java**

```java
package vip.mate.orchestrator.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.exception.MateClawException;
import vip.mate.group.model.GroupConversationEntity;
import vip.mate.group.repository.GroupConversationMapper;
import vip.mate.orchestrator.event.OrchestratorEventPublisher;
import vip.mate.orchestrator.model.OrchestratorAssignmentEntity;
import vip.mate.orchestrator.model.OrchestratorTaskEntity;
import vip.mate.orchestrator.repository.OrchestratorAssignmentMapper;
import vip.mate.orchestrator.repository.OrchestratorTaskMapper;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrchestratorEngine {

    private final OrchestratorTaskMapper taskMapper;
    private final OrchestratorAssignmentMapper assignmentMapper;
    private final ConversationService conversationService;
    private final ConversationMapper conversationMapper;
    private final AgentService agentService;
    private final AgentMapper agentMapper;
    private final MessageMapper messageMapper;
    private final GroupConversationMapper groupConversationMapper;
    private final OrchestratorEventPublisher eventPublisher;

    private static final int DEFAULT_MAX_PARALLEL = 8;
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int MAX_RETRY_COUNT = 1;
    private static final int MAX_RESULT_LENGTH = 4000;
    private static final ExecutorService VT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ConcurrentHashMap<Long, TaskExecutionContext> activeExecutions = new ConcurrentHashMap<>();

    // ─── Public API ───────────────────────────────────────────────

    public void execute(OrchestratorTaskEntity task) {
        TaskExecutionContext ctx = new TaskExecutionContext(task.getId());
        activeExecutions.put(task.getId(), ctx);

        try {
            // 1. Load assignments and parent conversation
            List<OrchestratorAssignmentEntity> assignments = assignmentMapper.selectList(
                new LambdaQueryWrapper<OrchestratorAssignmentEntity>()
                    .eq(OrchestratorAssignmentEntity::getTaskId, task.getId())
                    .orderByAsc(OrchestratorAssignmentEntity::getStepOrder));

            if (assignments.isEmpty()) {
                completeTask(task, ctx, "{}", 0, 0);
                return;
            }

            ConversationEntity parentConv = conversationMapper.selectById(task.getConversationId());
            if (parentConv == null) {
                throw new MateClawException("err.orchestrator.parent_conv_not_found",
                    "Parent conversation not found: " + task.getConversationId());
            }

            int maxParallel = resolveMaxParallel(task.getConversationId());
            ctx.semaphore = new Semaphore(maxParallel);
            ctx.failurePolicy = resolveFailurePolicy(task.getConversationId());

            // 2. Update task to RUNNING
            task.setStatus("running");
            task.setStartedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            String parentConvId = parentConv.getConversationId();

            eventPublisher.publishPlanUpdate(parentConvId, task.getId(), task.getTitle(),
                "running", assignments.size(), 0, 0);

            // 3. Build dependency graph
            Map<Long, OrchestratorAssignmentEntity> byStepOrder = new LinkedHashMap<>();
            for (OrchestratorAssignmentEntity a : assignments) {
                byStepOrder.put((long) a.getStepOrder(), a);
            }

            Map<Long, Integer> inDegree = new LinkedHashMap<>();   // assignmentId -> pending deps count
            Map<Long, List<Long>> reverseGraph = new HashMap<>();  // depId -> list of dependents

            for (OrchestratorAssignmentEntity a : assignments) {
                int deps = 0;
                if (a.getDependencyOn() != null) {
                    OrchestratorAssignmentEntity dep = byStepOrder.get(a.getDependencyOn());
                    if (dep != null) {
                        deps = 1;
                        reverseGraph.computeIfAbsent(dep.getId(), k -> new ArrayList<>()).add(a.getId());
                    }
                }
                inDegree.put(a.getId(), deps);
            }

            if (inDegree.values().stream().noneMatch(d -> d == 0)) {
                throw new MateClawException("err.orchestrator.cycle",
                    "Circular dependency detected in task plan");
            }

            // 4. Scheduling loop
            Object scheduleLock = new Object();
            java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(assignments.size());
            java.util.concurrent.atomic.AtomicInteger completed =
                new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicInteger failed =
                new java.util.concurrent.atomic.AtomicInteger(0);

            // Pre-load agent names for events
            Map<Long, String> agentNames = new HashMap<>();
            for (OrchestratorAssignmentEntity a : assignments) {
                if (!agentNames.containsKey(a.getAgentId())) {
                    AgentEntity agent = agentMapper.selectById(a.getAgentId());
                    agentNames.put(a.getAgentId(), agent != null ? agent.getName() : "unknown");
                }
            }

            scheduleReady(assignments, inDegree, reverseGraph, ctx, task, parentConv,
                parentConvId, agentNames, scheduleLock, remaining, completed, failed);

            // 5. Wait for completion
            synchronized (scheduleLock) {
                while (remaining.get() > 0 && !ctx.cancelled) {
                    scheduleLock.wait();
                }
            }

            if (ctx.cancelled) return;

            // 6. Aggregate and complete
            assignments = assignmentMapper.selectList(
                new LambdaQueryWrapper<OrchestratorAssignmentEntity>()
                    .eq(OrchestratorAssignmentEntity::getTaskId, task.getId())
                    .orderByAsc(OrchestratorAssignmentEntity::getStepOrder));

            String aggregated = aggregateResults(assignments);
            completeTask(task, ctx, aggregated, completed.get(), failed.get());

        } catch (Exception e) {
            log.error("Orchestrator task {} execution failed", task.getId(), e);
            task.setStatus("failed");
            task.setCompletedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            ConversationEntity parentConv = conversationMapper.selectById(task.getConversationId());
            if (parentConv != null) {
                eventPublisher.publishPlanUpdate(parentConv.getConversationId(), task.getId(),
                    task.getTitle(), "failed", 0, 0, 0);
            }
        } finally {
            activeExecutions.remove(task.getId());
        }
    }

    public void cancel(Long taskId) {
        TaskExecutionContext ctx = activeExecutions.get(taskId);
        if (ctx == null) return;
        ctx.cancelled = true;
        for (CompletableFuture<Void> f : ctx.futures.values()) {
            f.cancel(true);
        }
        // Update pending/running assignments to cancelled
        assignmentMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<OrchestratorAssignmentEntity>()
                .eq(OrchestratorAssignmentEntity::getTaskId, taskId)
                .in(OrchestratorAssignmentEntity::getStatus, List.of("pending", "running"))
                .set(OrchestratorAssignmentEntity::getStatus, "cancelled"));
    }

    // ─── Scheduling ───────────────────────────────────────────────

    private void scheduleReady(
            List<OrchestratorAssignmentEntity> assignments,
            Map<Long, Integer> inDegree,
            Map<Long, List<Long>> reverseGraph,
            TaskExecutionContext ctx,
            OrchestratorTaskEntity task,
            ConversationEntity parentConv,
            String parentConvId,
            Map<Long, String> agentNames,
            Object scheduleLock,
            java.util.concurrent.atomic.AtomicInteger remaining,
            java.util.concurrent.atomic.AtomicInteger completed,
            java.util.concurrent.atomic.AtomicInteger failed) {

        synchronized (scheduleLock) {
            if (ctx.cancelled) return;

            for (OrchestratorAssignmentEntity a : assignments) {
                if (!"pending".equals(a.getStatus())) continue;
                if (inDegree.getOrDefault(a.getId(), -1) != 0) continue;

                // Mark scheduled
                inDegree.put(a.getId(), -1);

                CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                    try {
                        ctx.semaphore.acquire();
                        if (!ctx.cancelled) {
                            executeSingle(a, task, parentConv, parentConvId,
                                agentNames.getOrDefault(a.getAgentId(), "unknown"), ctx);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        ctx.semaphore.release();
                    }
                }, VT_EXECUTOR).whenComplete((v, ex) -> {
                    synchronized (scheduleLock) {
                        ctx.futures.remove(a.getId());

                        if (ex != null && !ctx.cancelled) {
                            log.error("Assignment {} unexpected error", a.getId(), ex);
                            handleFailure(a, ctx, completed, failed);
                        }

                        // Release dependents
                        for (Long depId : reverseGraph.getOrDefault(a.getId(), List.of())) {
                            inDegree.merge(depId, -1, Integer::sum);
                        }

                        remaining.decrementAndGet();
                        scheduleReady(assignments, inDegree, reverseGraph, ctx, task,
                            parentConv, parentConvId, agentNames, scheduleLock,
                            remaining, completed, failed);
                        scheduleLock.notifyAll();
                    }
                });

                ctx.futures.put(a.getId(), f);
            }
        }
    }

    // ─── Single Assignment Execution ──────────────────────────────

    private void executeSingle(
            OrchestratorAssignmentEntity a,
            OrchestratorTaskEntity task,
            ConversationEntity parentConv,
            String parentConvId,
            String agentName,
            TaskExecutionContext ctx) {

        // Update to RUNNING
        a.setStatus("running");
        a.setStartedAt(LocalDateTime.now());
        assignmentMapper.updateById(a);

        eventPublisher.publishDelegationProgress(parentConvId, a.getId(), a.getAgentId(),
            agentName, "running", "Starting...");

        // Create child conversation
        String childConvId = "orch-" + task.getId() + "-" + a.getId();
        ConversationEntity childConv = conversationService.createChildConversation(
            childConvId, a.getAgentId(), parentConv.getUsername(),
            parentConv.getWorkspaceId(), parentConvId);
        a.setChildConversationId(childConv.getId());
        assignmentMapper.updateById(a);

        try {
            // Call agent and collect response
            Flux<String> stream = agentService.chatStream(
                a.getAgentId(), a.getGoal(), childConv.getConversationId());

            List<String> chunks = stream
                .take(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
                .collectList()
                .block(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS + 10));

            if (chunks == null) {
                // Timeout
                a.setStatus("failed");
                a.setErrorMessage("Timeout after " + DEFAULT_TIMEOUT_SECONDS + "s");
                a.setCompletedAt(LocalDateTime.now());
                assignmentMapper.updateById(a);

                eventPublisher.publishDelegationProgress(parentConvId, a.getId(), a.getAgentId(),
                    agentName, "failed", "Timeout");
                handleFailure(a, ctx, null, null);
                return;
            }

            String fullText = String.join("", chunks);
            String summary = fullText.length() > MAX_RESULT_LENGTH
                ? fullText.substring(0, MAX_RESULT_LENGTH) + "..."
                : fullText;

            a.setResultSummary(summary);
            a.setStatus("completed");
            a.setCompletedAt(LocalDateTime.now());
            assignmentMapper.updateById(a);

            eventPublisher.publishDelegationProgress(parentConvId, a.getId(), a.getAgentId(),
                agentName, "completed", truncateSummary(summary, 200));

            incrementTaskCounter(task.getId(), "completedAssignments");

        } catch (Exception e) {
            log.error("Assignment {} failed", a.getId(), e);
            a.setStatus("failed");
            a.setErrorMessage(truncateSummary(e.getMessage(), 500));
            a.setCompletedAt(LocalDateTime.now());
            assignmentMapper.updateById(a);

            eventPublisher.publishDelegationProgress(parentConvId, a.getId(), a.getAgentId(),
                agentName, "failed", truncateSummary(e.getMessage(), 200));
            handleFailure(a, ctx, null, null);
        }
    }

    private void handleFailure(
            OrchestratorAssignmentEntity a,
            TaskExecutionContext ctx,
            java.util.concurrent.atomic.AtomicInteger completed,
            java.util.concurrent.atomic.AtomicInteger failed) {

        // Auto-retry once
        int retries = a.getRetryCount() != null ? a.getRetryCount() : 0;
        if (retries < MAX_RETRY_COUNT) {
            a.setStatus("pending");
            a.setRetryCount(retries + 1);
            a.setErrorMessage(null);
            assignmentMapper.updateById(a);
            log.info("Assignment {} retry {}/{}", a.getId(), retries + 1, MAX_RETRY_COUNT);
            return;
        }

        incrementTaskCounter(a.getTaskId(), "failedAssignments");
        if (failed != null) failed.incrementAndGet();

        // fail_fast: cancel all in-flight
        if ("fail_fast".equals(ctx.failurePolicy)) {
            ctx.cancelled = true;
            for (CompletableFuture<Void> f : ctx.futures.values()) {
                f.cancel(true);
            }
        }
    }

    // ─── Task Completion ──────────────────────────────────────────

    private void completeTask(OrchestratorTaskEntity task, TaskExecutionContext ctx,
                               String aggregated, int completed, int failed) {
        // Write summary message
        ConversationEntity parentConv = conversationMapper.selectById(task.getConversationId());
        String parentConvId = parentConv != null ? parentConv.getConversationId() : "";

        MessageEntity msg = new MessageEntity();
        msg.setConversationId(parentConvId);
        msg.setRole("system");
        msg.setMessageType("system");
        msg.setContent(aggregated);
        msg.setStatus("completed");
        messageMapper.insert(msg);

        task.setStatus("completed");
        task.setCompletedAt(LocalDateTime.now());
        task.setAggregationMessageId(msg.getId());
        taskMapper.updateById(task);

        eventPublisher.publishPlanUpdate(parentConvId, task.getId(), task.getTitle(),
            "completed", task.getTotalAssignments(), completed, failed);
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private int resolveMaxParallel(Long conversationId) {
        GroupConversationEntity gc = groupConversationMapper.selectOne(
            new LambdaQueryWrapper<GroupConversationEntity>()
                .eq(GroupConversationEntity::getConversationId, conversationId));
        if (gc != null && gc.getMaxParallelTasks() != null && gc.getMaxParallelTasks() > 0) {
            return Math.min(gc.getMaxParallelTasks(), 8);
        }
        return DEFAULT_MAX_PARALLEL;
    }

    private String resolveFailurePolicy(Long conversationId) {
        GroupConversationEntity gc = groupConversationMapper.selectOne(
            new LambdaQueryWrapper<GroupConversationEntity>()
                .eq(GroupConversationEntity::getConversationId, conversationId));
        if (gc != null && gc.getFailurePolicy() != null) {
            return gc.getFailurePolicy();
        }
        return "fail_tolerant";
    }

    private String aggregateResults(List<OrchestratorAssignmentEntity> assignments) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Orchestrator Task Summary\n\n");
        for (OrchestratorAssignmentEntity a : assignments) {
            String icon = "completed".equals(a.getStatus()) ? "✅" : "❌";
            sb.append(icon).append(" **Step ").append(a.getStepOrder()).append("**");
            if (a.getResultSummary() != null && !a.getResultSummary().isBlank()) {
                sb.append(": ").append(truncateSummary(a.getResultSummary(), 300));
            } else if (a.getErrorMessage() != null) {
                sb.append(": ").append(a.getErrorMessage());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String truncateSummary(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private void incrementTaskCounter(Long taskId, String field) {
        OrchestratorTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) return;
        if ("completedAssignments".equals(field)) {
            task.setCompletedAssignments((task.getCompletedAssignments() != null ? task.getCompletedAssignments() : 0) + 1);
        } else {
            task.setFailedAssignments((task.getFailedAssignments() != null ? task.getFailedAssignments() : 0) + 1);
        }
        taskMapper.updateById(task);
    }

    // ─── Inner class ──────────────────────────────────────────────

    static class TaskExecutionContext {
        final Long taskId;
        final ConcurrentHashMap<Long, CompletableFuture<Void>> futures = new ConcurrentHashMap<>();
        volatile boolean cancelled = false;
        Semaphore semaphore;
        String failurePolicy = "fail_tolerant";

        TaskExecutionContext(Long taskId) {
            this.taskId = taskId;
        }
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
cd D:/code/Loom/mateclaw-dev && ./mvnw compile -pl mateclaw-server -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
cd D:/code/Loom && git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/orchestrator/engine/ && git commit -m "feat: add OrchestratorEngine for multi-agent execution scheduling"
```

---

### Task 2: Modify OrchestratorService.java

**Files:**
- Modify: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\orchestrator\service\OrchestratorService.java`

- [ ] **Step 1: Add OrchestratorEngine injection and async execution in createTask()**

Replace the file content to add the engine field and async trigger. The key changes:

**At line 20** (inside class body, after existing fields), add:

```java
private final OrchestratorEngine engine;
```

The `@RequiredArgsConstructor` will auto-generate the constructor with the new field since it's `final`.

**At line 39** (after `taskMapper.insert(task);`), add after the assignment creation loop (after line 55, before the `return task;` on line 56):

```java
        CompletableFuture.runAsync(() -> engine.execute(task));
```

So the `createTask` method becomes:

```java
@Transactional
public OrchestratorTaskEntity createTask(Long conversationId, Long messageId,
                                          String title, String planJson,
                                          List<Map<String, Object>> steps) {
    OrchestratorTaskEntity task = new OrchestratorTaskEntity();
    task.setConversationId(conversationId);
    task.setMessageId(messageId);
    task.setTitle(title);
    task.setPlanJson(planJson);
    task.setStatus("pending");
    task.setTotalAssignments(steps.size());
    task.setCompletedAssignments(0);
    task.setFailedAssignments(0);
    task.setStartedAt(LocalDateTime.now());
    taskMapper.insert(task);

    for (int i = 0; i < steps.size(); i++) {
        Map<String, Object> step = steps.get(i);
        OrchestratorAssignmentEntity a = new OrchestratorAssignmentEntity();
        a.setTaskId(task.getId());
        a.setAgentId(Long.valueOf(step.get("agentId").toString()));
        a.setStepOrder(i + 1);
        a.setExecutionMode((String) step.getOrDefault("mode", "sequential"));
        a.setGoal((String) step.get("goal"));
        if (step.containsKey("dependsOn")) {
            a.setDependencyOn(Long.valueOf(step.get("dependsOn").toString()));
        }
        a.setStatus("pending");
        a.setRetryCount(0);
        assignmentMapper.insert(a);
    }

    CompletableFuture.runAsync(() -> engine.execute(task));

    return task;
}
```

- [ ] **Step 2: Add retry integration in retryAssignments()**

At the end of `retryAssignments()` (after line 104, before the closing `}`), add:

```java
        if (!toRetry.isEmpty()) {
            CompletableFuture.runAsync(() -> {
                OrchestratorTaskEntity task = taskMapper.selectById(taskId);
                if (task != null) engine.execute(task);
            });
        }
```

`execute(task)` processes all assignments with `status='pending'`, so one call covers all retried assignments.

Note: Add the import `import java.util.concurrent.CompletableFuture;` to the imports section (after line 14's existing `import java.util.*;`, `CompletableFuture` is already covered).

- [ ] **Step 3: Add import for OrchestratorEngine**

Add to imports (after line 11):

```java
import vip.mate.orchestrator.engine.OrchestratorEngine;
```

- [ ] **Step 4: Verify compilation**

```bash
cd D:/code/Loom/mateclaw-dev && ./mvnw compile -pl mateclaw-server -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
cd D:/code/Loom && git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/orchestrator/service/OrchestratorService.java && git commit -m "feat: wire OrchestratorEngine into OrchestratorService for async execution"
```

---

### Task 3: Modify OrchestratorEventPublisher.java

**Files:**
- Modify: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\orchestrator\event\OrchestratorEventPublisher.java`

- [ ] **Step 1: Replace the class with updated signatures matching SSE protocol**

Replace the entire file with:

```java
package vip.mate.orchestrator.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.channel.web.ChatStreamTracker;

import java.util.Map;

/**
 * Publishes Orchestrator SSE events per API.txt 1.7 SSE Streaming Protocol.
 * <pre>
 * event: delegation_progress
 * data: {"agentName":"CodeBot","agentId":5,"assignmentId":12,"status":"running","summary":"Working..."}
 *
 * event: orchestrator_plan
 * data: {"taskId":101,"conversationId":5,"title":"Build landing page","status":"running","totalAssignments":3,"completedAssignments":1,"failedAssignments":0}
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrchestratorEventPublisher {

    private final ChatStreamTracker streamTracker;

    /**
     * Per-assignment lifecycle event.
     */
    public void publishDelegationProgress(String conversationId, Long assignmentId,
                                           Long agentId, String agentName,
                                           String status, String summary) {
        streamTracker.broadcastObject(conversationId, "delegation_progress",
            Map.of("agentName", agentName != null ? agentName : "",
                   "agentId", agentId != null ? agentId : 0,
                   "assignmentId", assignmentId != null ? assignmentId : 0,
                   "status", status != null ? status : "",
                   "summary", summary != null ? summary : ""));
        log.debug("delegation_progress conv={} assign={} agent={} status={}",
            conversationId, assignmentId, agentName, status);
    }

    /**
     * Task-level lifecycle event.
     */
    public void publishPlanUpdate(String conversationId, Long taskId, String title,
                                   String status, int totalAssignments,
                                   int completedAssignments, int failedAssignments) {
        streamTracker.broadcastObject(conversationId, "orchestrator_plan",
            Map.of("conversationId", conversationId != null ? conversationId : "",
                   "taskId", taskId != null ? taskId : 0,
                   "title", title != null ? title : "",
                   "status", status != null ? status : "",
                   "totalAssignments", totalAssignments,
                   "completedAssignments", completedAssignments,
                   "failedAssignments", failedAssignments));
        log.debug("orchestrator_plan conv={} task={} status={} {}/{}",
            conversationId, taskId, status, completedAssignments, totalAssignments);
    }
}
```

The old `publishPlan` (with `planJson` parameter) and `publishArtifactPreview` methods are removed. The `publishDelegationProgress` method is updated to include `assignmentId` and `agentId` fields.

- [ ] **Step 2: Verify no callers are broken**

```bash
cd D:/code/Loom/mateclaw-dev && grep -rn "publishPlan\b" --include="*.java" mateclaw-server/src/main/java/
```

Check that `publishPlan` (old method name) has no remaining callers outside the orchestrator package. If any exist, update them to use `publishPlanUpdate`.

```bash
cd D:/code/Loom/mateclaw-dev && grep -rn "publishArtifactPreview\b" --include="*.java" mateclaw-server/src/main/java/
```

Check that `publishArtifactPreview` has no remaining callers. If any exist, keep the method as a deprecated pass-through.

- [ ] **Step 3: Verify compilation**

```bash
cd D:/code/Loom/mateclaw-dev && ./mvnw compile -pl mateclaw-server -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
cd D:/code/Loom && git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/orchestrator/event/OrchestratorEventPublisher.java && git commit -m "feat: update OrchestratorEventPublisher signatures to match SSE protocol spec"
```
