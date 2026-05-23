package vip.mate.orchestrator.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.channel.web.ChatStreamTracker;

import java.util.Map;

/**
 * 发布 Orchestrator SSE 事件 — API 文档 1.7 SSE Protocol
 * 通过 ChatStreamTracker.broadcastObject 发送 orchestrator_plan 和 delegation_progress 事件
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrchestratorEventPublisher {

    private final ChatStreamTracker streamTracker;

    public void publishPlan(String conversationId, Long taskId, String title, String planJson) {
        streamTracker.broadcastObject(conversationId, "orchestrator_plan",
            Map.of("taskId", taskId, "title", title, "planJson", planJson));
        log.debug("Published orchestrator_plan for conversation {}: task {}", conversationId, taskId);
    }

    public void publishDelegationProgress(String conversationId, String agentName,
                                           String status, String summary) {
        streamTracker.broadcastObject(conversationId, "delegation_progress",
            Map.of("agentName", agentName, "status", status, "summary", summary));
    }

    public void publishArtifactPreview(String conversationId, Long artifactId,
                                        String type, String previewUrl) {
        streamTracker.broadcastObject(conversationId, "artifact_preview",
            Map.of("artifactId", artifactId, "type", type, "previewUrl", previewUrl));
    }

    /** Plan status update (running / completed / failed) with counters. */
    public void publishPlanUpdate(String conversationId, Long taskId, String title,
                                   String status, int totalAssignments,
                                   int completedAssignments, int failedAssignments) {
        streamTracker.broadcastObject(conversationId, "orchestrator_plan_update",
            Map.of("taskId", taskId, "title", title, "status", status,
                "totalAssignments", totalAssignments,
                "completedAssignments", completedAssignments,
                "failedAssignments", failedAssignments));
    }

    /** Delegation progress with assignment/agent ids. */
    public void publishDelegationProgress(String conversationId, Long assignmentId,
                                           Long agentId, String agentName,
                                           String status, String summary) {
        streamTracker.broadcastObject(conversationId, "delegation_progress",
            Map.of("assignmentId", assignmentId, "agentId", agentId,
                "agentName", agentName, "status", status, "summary", summary));
    }
}
