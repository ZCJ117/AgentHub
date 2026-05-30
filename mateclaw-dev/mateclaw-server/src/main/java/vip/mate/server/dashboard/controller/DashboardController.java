package vip.mate.server.dashboard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.domain.dashboard.service.DashboardService;
import vip.mate.domain.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Map;

/**
 * Dashboard 统计接口
 *
 * @author MateClaw Team
 */
@Tag(name = "Dashboard")
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "获取概览统计")
    @GetMapping("/overview")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> overview(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(dashboardService.getOverview(workspaceId));
    }

    @Operation(summary = "获取日用量趋势")
    @GetMapping("/trend")
    @RequireWorkspaceRole("member")
    public R<List<Map<String, Object>>> trend(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(defaultValue = "30") int days) {
        return R.ok(dashboardService.getTrend(workspaceId, Math.min(days, 90)));
    }
}
