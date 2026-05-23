package vip.mate.orchestrator.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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

            task.setStatus("running");
            task.setStartedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            String parentConvId = parentConv.getConversationId();

            eventPublisher.publishPlanUpdate(parentConvId, task.getId(), task.getTitle(),
                "running", assignments.size(), 0, 0);

            Map<Long, OrchestratorAssignmentEntity> byStepOrder = new LinkedHashMap<>();
            for (OrchestratorAssignmentEntity a : assignments) {
                byStepOrder.put((long) a.getStepOrder(), a);
            }

            Map<Long, Integer> inDegree = new LinkedHashMap<>();
            Map<Long, List<Long>> reverseGraph = new HashMap<>();

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

            Object scheduleLock = new Object();
            java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(assignments.size());
            java.util.concurrent.atomic.AtomicInteger completed =
                new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicInteger failed =
                new java.util.concurrent.atomic.AtomicInteger(0);

            Map<Long, String> agentNames = new HashMap<>();
            for (OrchestratorAssignmentEntity a : assignments) {
                if (!agentNames.containsKey(a.getAgentId())) {
                    AgentEntity agent = agentMapper.selectById(a.getAgentId());
                    agentNames.put(a.getAgentId(), agent != null ? agent.getName() : "unknown");
                }
            }

            scheduleReady(assignments, inDegree, reverseGraph, ctx, task, parentConv,
                parentConvId, agentNames, scheduleLock, remaining, completed, failed);

            synchronized (scheduleLock) {
                while (remaining.get() > 0 && !ctx.cancelled) {
                    scheduleLock.wait();
                }
            }

            if (ctx.cancelled) {
                task.setStatus("cancelled");
                task.setCompletedAt(LocalDateTime.now());
                taskMapper.updateById(task);
                return;
            }

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
        assignmentMapper.update(null,
            new LambdaUpdateWrapper<OrchestratorAssignmentEntity>()
                .eq(OrchestratorAssignmentEntity::getTaskId, taskId)
                .in(OrchestratorAssignmentEntity::getStatus, List.of("pending", "running"))
                .set(OrchestratorAssignmentEntity::getStatus, "cancelled"));

        OrchestratorTaskEntity task = taskMapper.selectById(taskId);
        if (task != null) {
            task.setStatus("cancelled");
            task.setCompletedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        }
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

                inDegree.put(a.getId(), -1);

                CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                    try {
                        ctx.semaphore.acquire();
                        if (!ctx.cancelled) {
                            executeSingle(a, task, parentConv, parentConvId,
                                agentNames.getOrDefault(a.getAgentId(), "unknown"), ctx, inDegree);
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
                            handleFailure(a, ctx, completed, failed, inDegree);
                        }

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
            TaskExecutionContext ctx,
            Map<Long, Integer> inDegree) {

        a.setStatus("running");
        a.setStartedAt(LocalDateTime.now());
        assignmentMapper.updateById(a);

        eventPublisher.publishDelegationProgress(parentConvId, a.getId(), a.getAgentId(),
            agentName, "running", "Starting...");

        String childConvId = "orch-" + task.getId() + "-" + a.getId();
        ConversationEntity childConv = conversationService.createChildConversation(
            childConvId, a.getAgentId(), parentConv.getUsername(),
            parentConv.getWorkspaceId(), parentConvId);
        a.setChildConversationId(childConv.getId());
        assignmentMapper.updateById(a);

        try {
            Flux<String> stream = agentService.chatStream(
                a.getAgentId(), a.getGoal(), childConv.getConversationId());

            List<String> chunks = stream
                .take(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
                .collectList()
                .block(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS + 10));

            if (chunks == null) {
                a.setStatus("failed");
                a.setErrorMessage("Timeout after " + DEFAULT_TIMEOUT_SECONDS + "s");
                a.setCompletedAt(LocalDateTime.now());
                assignmentMapper.updateById(a);

                eventPublisher.publishDelegationProgress(parentConvId, a.getId(), a.getAgentId(),
                    agentName, "failed", "Timeout");
                handleFailure(a, ctx, null, null, inDegree);
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
            handleFailure(a, ctx, null, null, inDegree);
        }
    }

    private void handleFailure(
            OrchestratorAssignmentEntity a,
            TaskExecutionContext ctx,
            java.util.concurrent.atomic.AtomicInteger completed,
            java.util.concurrent.atomic.AtomicInteger failed,
            Map<Long, Integer> inDegree) {

        int retries = a.getRetryCount() != null ? a.getRetryCount() : 0;
        if (retries < MAX_RETRY_COUNT) {
            a.setStatus("pending");
            a.setRetryCount(retries + 1);
            a.setErrorMessage(null);
            assignmentMapper.updateById(a);
            inDegree.put(a.getId(), 0);
            log.info("Assignment {} retry {}/{}", a.getId(), retries + 1, MAX_RETRY_COUNT);
            return;
        }

        incrementTaskCounter(a.getTaskId(), "failedAssignments");
        if (failed != null) failed.incrementAndGet();

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
        if ("completedAssignments".equals(field)) {
            taskMapper.update(null,
                new LambdaUpdateWrapper<OrchestratorTaskEntity>()
                    .eq(OrchestratorTaskEntity::getId, taskId)
                    .setSql("completed_assignments = COALESCE(completed_assignments, 0) + 1"));
        } else {
            taskMapper.update(null,
                new LambdaUpdateWrapper<OrchestratorTaskEntity>()
                    .eq(OrchestratorTaskEntity::getId, taskId)
                    .setSql("failed_assignments = COALESCE(failed_assignments, 0) + 1"));
        }
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
