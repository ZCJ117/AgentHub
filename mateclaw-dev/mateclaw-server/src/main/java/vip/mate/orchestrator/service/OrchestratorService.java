package vip.mate.orchestrator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import vip.mate.exception.MateClawException;
import vip.mate.orchestrator.model.*;
import vip.mate.orchestrator.engine.OrchestratorEngine;
import vip.mate.orchestrator.repository.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestratorService {

    private final OrchestratorTaskMapper taskMapper;
    private final OrchestratorAssignmentMapper assignmentMapper;
    private final OrchestratorEngine engine;

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
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                CompletableFuture.runAsync(() -> engine.execute(task));
            }
        });
        return task;
    }

    public IPage<OrchestratorTaskEntity> listTasks(Long conversationId, String status, int page, int size) {
        Page<OrchestratorTaskEntity> pageReq = new Page<>(page, size);
        LambdaQueryWrapper<OrchestratorTaskEntity> qw = new LambdaQueryWrapper<>();
        if (conversationId != null) qw.eq(OrchestratorTaskEntity::getConversationId, conversationId);
        if (status != null && !status.isBlank()) qw.eq(OrchestratorTaskEntity::getStatus, status);
        qw.orderByDesc(OrchestratorTaskEntity::getCreatedAt);
        return taskMapper.selectPage(pageReq, qw);
    }

    public OrchestratorTaskEntity getTaskDetail(Long taskId) {
        OrchestratorTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) throw new MateClawException("err.orchestrator.task_not_found", "任务不存在");
        return task;
    }

    public List<OrchestratorAssignmentEntity> getAssignments(Long taskId) {
        return assignmentMapper.selectList(
            new LambdaQueryWrapper<OrchestratorAssignmentEntity>()
                .eq(OrchestratorAssignmentEntity::getTaskId, taskId)
                .orderByAsc(OrchestratorAssignmentEntity::getStepOrder));
    }

    public OrchestratorAssignmentEntity getAssignmentDetail(Long id) {
        OrchestratorAssignmentEntity a = assignmentMapper.selectById(id);
        if (a == null) throw new MateClawException("err.orchestrator.assignment_not_found", "分派不存在");
        return a;
    }

    @Transactional
    public void retryAssignments(Long taskId, List<Long> assignmentIds) {
        List<OrchestratorAssignmentEntity> toRetry;
        if (assignmentIds == null || assignmentIds.isEmpty()) {
            toRetry = assignmentMapper.selectList(
                new LambdaQueryWrapper<OrchestratorAssignmentEntity>()
                    .eq(OrchestratorAssignmentEntity::getTaskId, taskId)
                    .eq(OrchestratorAssignmentEntity::getStatus, "failed"));
        } else {
            toRetry = assignmentMapper.selectList(
                new LambdaQueryWrapper<OrchestratorAssignmentEntity>()
                    .eq(OrchestratorAssignmentEntity::getTaskId, taskId)
                    .in(OrchestratorAssignmentEntity::getId, assignmentIds));
        }
        for (OrchestratorAssignmentEntity a : toRetry) {
            a.setStatus("pending");
            a.setRetryCount((a.getRetryCount() != null ? a.getRetryCount() : 0) + 1);
            a.setErrorMessage(null);
            a.setStartedAt(LocalDateTime.now());
            assignmentMapper.updateById(a);
        }
        if (!toRetry.isEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    CompletableFuture.runAsync(() -> {
                        OrchestratorTaskEntity task = taskMapper.selectById(taskId);
                        if (task != null) engine.execute(task);
                    });
                }
            });
        }
    }

    @Transactional
    public void cancelTask(Long taskId) {
        engine.cancel(taskId);
        OrchestratorTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) throw new MateClawException("err.orchestrator.task_not_found", "任务不存在");
        task.setStatus("cancelled");
        task.setCompletedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        assignmentMapper.update(null,
            new LambdaUpdateWrapper<OrchestratorAssignmentEntity>()
                .eq(OrchestratorAssignmentEntity::getTaskId, taskId)
                .in(OrchestratorAssignmentEntity::getStatus, List.of("pending", "running"))
                .set(OrchestratorAssignmentEntity::getStatus, "cancelled"));
    }
}
