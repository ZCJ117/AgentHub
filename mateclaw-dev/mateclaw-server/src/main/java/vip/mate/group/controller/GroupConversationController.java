package vip.mate.group.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.group.service.GroupConversationService;

import java.util.List;
import java.util.Map;

/**
 * 群聊会话管理接口 — API 文档 6.
 */
@Slf4j
@Tag(name = "群聊会话管理")
@RestController
@RequestMapping("/api/v1/conversations/group")
@RequiredArgsConstructor
public class GroupConversationController {

    private final GroupConversationService groupConversationService;

    @Operation(summary = "创建群聊会话")
    @PostMapping
    public R<Map<String, Object>> create(
            Authentication auth,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestBody Map<String, Object> body) {
        String username = auth != null ? auth.getName() : "anonymous";
        String title = (String) body.getOrDefault("title", "Group Chat");
        Long orchestratorId = body.get("orchestratorAgentId") != null
            ? Long.valueOf(body.get("orchestratorAgentId").toString()) : null;
        List<Long> agentIds = safeConvertToLongList(body.get("agentIds"));
        String schedulingMode = (String) body.getOrDefault("schedulingMode", "auto");
        String failurePolicy = (String) body.getOrDefault("failurePolicy", "fail_tolerant");
        Integer maxParallel = body.get("maxParallelTasks") != null
            ? Integer.valueOf(body.get("maxParallelTasks").toString()) : 8;
        Map<String, Object> result = groupConversationService.createGroup(username, workspaceId, title,
            orchestratorId, agentIds, schedulingMode, failurePolicy, maxParallel);
        Long conversationDbId = Long.valueOf(result.get("conversationId").toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> groupConfig = (Map<String, Object>) result.get("groupConfig");
        Long actualOrchestratorId = Long.valueOf(groupConfig.get("orchestratorAgentId").toString());
        try {
            groupConversationService.generateClaudeMdFiles(conversationDbId, actualOrchestratorId, agentIds);
        } catch (Exception e) {
            log.warn("CLAUDE.md generation failed for group {}: {}", conversationDbId, e.getMessage());
        }
        return R.ok(result);
    }

    @Operation(summary = "获取群聊列表")
    @GetMapping
    public R<IPage<Map<String, Object>>> list(
            Authentication auth,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String username = auth != null ? auth.getName() : "anonymous";
        return R.ok(groupConversationService.listGroups(username, page, size));
    }

    @Operation(summary = "获取群聊详情")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(groupConversationService.getGroupDetail(id));
    }

    @Operation(summary = "更新群聊配置")
    @PutMapping("/{id}")
    public R<Void> updateConfig(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        groupConversationService.updateGroupConfig(id, body);
        return R.ok();
    }

    @Operation(summary = "批量替换成员")
    @PutMapping("/{id}/members")
    public R<Void> batchReplaceMembers(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        List<Long> agentIds = safeConvertToLongList(body.get("agentIds"));
        groupConversationService.batchReplaceMembers(id, agentIds);
        return R.ok();
    }

    @Operation(summary = "添加成员")
    @PostMapping("/{id}/members")
    public R<Void> addMember(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long agentId = Long.valueOf(body.get("agentId").toString());
        String role = (String) body.getOrDefault("memberRole", "member");
        groupConversationService.addMember(id, agentId, role);
        return R.ok();
    }

    @Operation(summary = "移除成员")
    @DeleteMapping("/{id}/members/{agentId}")
    public R<Void> removeMember(@PathVariable Long id, @PathVariable Long agentId) {
        groupConversationService.removeMember(id, agentId);
        return R.ok();
    }

    private static List<Long> safeConvertToLongList(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        return list.stream().map(Object::toString).map(Long::valueOf).toList();
    }
}
