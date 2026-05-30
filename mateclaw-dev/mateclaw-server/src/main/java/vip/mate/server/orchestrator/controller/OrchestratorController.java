package vip.mate.server.orchestrator;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.domain.orchestrator.model.*;
import vip.mate.domain.orchestrator.service.OrchestratorService;

import java.util.List;
import java.util.Map;

@Tag(name = "Orchestrator 调度")
@RestController
@RequestMapping("/api/v1/orchestrator")
@RequiredArgsConstructor
public class OrchestratorController {

    private final OrchestratorService orchestratorService;

    @Operation(summary = "查询任务列表")
    @GetMapping("/tasks")
    public R<IPage<OrchestratorTaskEntity>> listTasks(
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(orchestratorService.listTasks(conversationId, status, page, size));
    }

    @Operation(summary = "获取任务详情")
    @GetMapping("/tasks/{taskId}")
    public R<OrchestratorTaskEntity> getTask(@PathVariable Long taskId) {
        return R.ok(orchestratorService.getTaskDetail(taskId));
    }

    @Operation(summary = "获取任务分派列表")
    @GetMapping("/tasks/{taskId}/assignments")
    public R<List<OrchestratorAssignmentEntity>> getAssignments(@PathVariable Long taskId) {
        return R.ok(orchestratorService.getAssignments(taskId));
    }

    @Operation(summary = "获取单个分派详情")
    @GetMapping("/assignments/{id}")
    public R<OrchestratorAssignmentEntity> getAssignment(@PathVariable Long id) {
        return R.ok(orchestratorService.getAssignmentDetail(id));
    }

    @SuppressWarnings("unchecked")
    @Operation(summary = "重试失败的分派")
    @PostMapping("/tasks/{taskId}/retry")
    public R<Void> retry(@PathVariable Long taskId,
                         @RequestBody(required = false) Map<String, Object> body) {
        List<Long> ids = body != null && body.containsKey("assignmentIds")
            ? ((List<Object>) body.get("assignmentIds")).stream().map(Object::toString).map(Long::valueOf).toList()
            : null;
        orchestratorService.retryAssignments(taskId, ids);
        return R.ok();
    }

    @Operation(summary = "取消任务")
    @PostMapping("/tasks/{taskId}/cancel")
    public R<Void> cancel(@PathVariable Long taskId) {
        orchestratorService.cancelTask(taskId);
        return R.ok();
    }
}
