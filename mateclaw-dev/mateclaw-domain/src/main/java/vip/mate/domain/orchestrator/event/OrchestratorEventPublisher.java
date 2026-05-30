package vip.mate.domain.orchestrator.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.infra.channel.web.ChatStreamTracker;

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
