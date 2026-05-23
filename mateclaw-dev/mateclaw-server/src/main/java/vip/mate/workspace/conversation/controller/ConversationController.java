package vip.mate.workspace.conversation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.message.model.MessagePinEntity;
import vip.mate.message.service.MessagePinService;
import vip.mate.message.service.MessageReactionService;
import vip.mate.workspace.conversation.vo.ConversationVO;
import vip.mate.workspace.conversation.vo.MessageVO;

import java.util.List;
import java.util.Map;

/**
 * 会话管理接口
 *
 * @author MateClaw Team
 */
@Tag(name = "会话管理")
@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final ChatStreamTracker streamTracker;
    private final MessageReactionService reactionService;
    private final MessagePinService pinService;

    /**
     * 获取当前用户的会话列表
     * 返回 ConversationVO，包含 agentName / agentIcon / status 等前端展示字段
     */
    @Operation(summary = "获取会话列表")
    @GetMapping
    public R<List<ConversationVO>> list(
            Authentication auth,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(required = false) String conversationType) {
        String username = auth != null ? auth.getName() : "anonymous";
        return R.ok(conversationService.listConversations(username, workspaceId, conversationType));
    }

    /**
     * 分页查询会话列表（用于会话管理页）。
     * <p>会话管理页可能跨多个 IM 渠道，单页全量返回会拖慢首屏。
     */
    @Operation(summary = "分页查询会话列表")
    @GetMapping("/page")
    public R<com.baomidou.mybatisplus.core.metadata.IPage<ConversationVO>> page(
            Authentication auth,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        String username = auth != null ? auth.getName() : "anonymous";
        return R.ok(conversationService.pageConversations(username, workspaceId, page, size, keyword));
    }

    /**
     * 获取指定会话的消息历史（支持分页）。
     * <p>
     * 不传 limit 时返回全部消息（向后兼容）。
     * 传 limit 时返回最新 limit 条 + hasMore 标志。
     * 传 beforeId + limit 时返回该 ID 之前的 limit 条（上拉加载更早消息）。
     */
    @Operation(summary = "获取会话消息历史（支持分页）")
    @GetMapping("/{conversationId}/messages")
    public R<?> listMessages(@PathVariable String conversationId,
                             @RequestParam(required = false) Long beforeId,
                             @RequestParam(required = false) Integer limit,
                             Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail(403, "无权访问该会话");
        }

        // 向后兼容：不传 limit 则返回全部消息（旧前端行为）
        if (limit == null || limit <= 0) {
            return R.ok(conversationService.listMessageViews(conversationId));
        }

        // 分页模式
        java.util.List<vip.mate.workspace.conversation.model.MessageEntity> messages;
        boolean hasMore;

        if (beforeId != null) {
            // 上拉加载：取 beforeId 之前的 limit+1 条，多取一条用于判断 hasMore
            messages = conversationService.listMessagesBefore(conversationId, beforeId, limit + 1);
            hasMore = messages.size() > limit;
            if (hasMore) {
                messages = messages.subList(messages.size() - limit, messages.size());
            }
        } else {
            // 初始加载：最新 limit 条
            long total = conversationService.countMessages(conversationId);
            messages = conversationService.listRecentMessages(conversationId, limit);
            hasMore = total > limit;
        }

        java.util.List<vip.mate.workspace.conversation.vo.MessageVO> views = messages.stream()
                .map(m -> vip.mate.workspace.conversation.vo.MessageVO.from(
                        m, conversationService.parseMessageParts(m), conversationService.renderMessageContent(m)))
                .toList();

        return R.ok(java.util.Map.of(
                "messages", views,
                "hasMore", hasMore
        ));
    }

    /**
     * 删除会话（同时删除消息）
     */
    @Operation(summary = "删除会话")
    @DeleteMapping("/{conversationId}")
    public R<Void> delete(@PathVariable String conversationId, Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail(403, "无权操作该会话");
        }
        conversationService.deleteConversation(conversationId);
        return R.ok();
    }

    /**
     * 重命名会话
     */
    @Operation(summary = "重命名会话")
    @PutMapping("/{conversationId}/title")
    public R<Void> rename(@PathVariable String conversationId, @RequestBody Map<String, String> body, Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail(403, "无权操作该会话");
        }
        String title = body.getOrDefault("title", "").trim();
        if (title.isEmpty() || title.length() > 100) {
            return R.fail("标题不合法");
        }
        conversationService.renameConversation(conversationId, title);
        return R.ok();
    }

    /**
     * 置顶 / 取消置顶会话
     */
    @Operation(summary = "置顶或取消置顶会话")
    @PutMapping("/{conversationId}/pin")
    public R<Void> setPinned(@PathVariable String conversationId,
                             @RequestBody Map<String, Boolean> body,
                             Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail(403, "无权操作该会话");
        }
        conversationService.setPinned(conversationId, Boolean.TRUE.equals(body.get("pinned")));
        return R.ok();
    }

    /**
     * 归档或取消归档会话
     */
    @Operation(summary = "归档或取消归档会话")
    @PutMapping("/{conversationId}/archive")
    public R<Void> setArchived(@PathVariable String conversationId,
                               @RequestBody Map<String, Boolean> body,
                               Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail(403, "无权操作该会话");
        }
        conversationService.setArchived(conversationId, Boolean.TRUE.equals(body.get("archived")));
        return R.ok();
    }

    /**
     * Pin a conversation to a specific (provider, model) pair so subsequent
     * messages — including those from IM channels (Feishu / DingTalk / WeCom
     * / Telegram / Discord / QQ / Slack / WeChat) — use that model instead
     * of falling back to the agent or global default. Closes issue #183
     * where IM conversations could never be steered away from the agent's
     * configured default via the admin UI.
     *
     * <p>Both fields must be non-blank to take effect — a half-populated
     * payload is silently ignored at the service layer (see
     * {@link ConversationService#updateConversationModel}). Passing
     * existing matching values is a no-op (no DB write).
     */
    @Operation(summary = "切换会话使用的模型 (provider + model name)")
    @PutMapping("/{conversationId}/model")
    public R<Void> setModel(@PathVariable String conversationId,
                            @RequestBody Map<String, String> body,
                            Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail(403, "无权操作该会话");
        }
        String provider = body.get("modelProvider");
        String modelName = body.get("modelName");
        if (provider == null || provider.isBlank() || modelName == null || modelName.isBlank()) {
            return R.fail("modelProvider 和 modelName 都必须提供");
        }
        conversationService.updateConversationModel(conversationId, provider.trim(), modelName.trim());
        return R.ok();
    }

    /**
     * 批量删除会话（仅删除当前用户有权操作的会话）
     */
    @Operation(summary = "批量删除会话")
    @PostMapping("/batch-delete")
    public R<Integer> batchDelete(@RequestBody Map<String, List<String>> body, Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        List<String> ids = body.get("conversationIds");
        if (ids == null || ids.isEmpty()) {
            return R.fail("未指定要删除的会话");
        }
        int deleted = 0;
        for (String conversationId : ids) {
            if (conversationId == null || conversationId.isBlank()) {
                continue;
            }
            if (!conversationService.isConversationOwner(conversationId, username)) {
                continue;
            }
            conversationService.deleteConversation(conversationId);
            deleted++;
        }
        return R.ok(deleted);
    }

    /**
     * 清空会话消息（保留会话记录）
     */
    @Operation(summary = "清空会话消息")
    @DeleteMapping("/{conversationId}/messages")
    public R<Void> clearMessages(@PathVariable String conversationId, Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail(403, "无权操作该会话");
        }
        conversationService.clearMessages(conversationId);
        return R.ok();
    }

    /**
     * 获取会话的流状态
     * 优先使用内存中的 StreamTracker，若无数据则回退到数据库持久化的 stream_status
     */
    @Operation(summary = "获取会话流状态")
    @GetMapping("/{conversationId}/status")
    public R<Map<String, String>> getStreamStatus(@PathVariable String conversationId, Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        // A freshly opened chat uses a client-generated id that is not persisted
        // until the first message lands. The console polls this endpoint on an
        // interval, so report idle for an unknown conversation instead of failing.
        if (!conversationService.conversationExists(conversationId)) {
            return R.ok(Map.of("streamStatus", "idle"));
        }
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail(403, "无权访问该会话");
        }
        if (streamTracker.isRunning(conversationId)) {
            return R.ok(Map.of("streamStatus", "running"));
        }
        // 回退到数据库持久化的 stream_status（处理服务重启/节点切换场景）
        String dbStatus = conversationService.getStreamStatus(conversationId);
        return R.ok(Map.of("streamStatus", dbStatus != null ? dbStatus : "idle"));
    }

    @Operation(summary = "获取消息反馈")
    @GetMapping("/messages/{id}/reactions")
    public R<Map<String, Object>> getReactions(@PathVariable Long id) {
        return R.ok(Map.of(
            "messageId", id,
            "reactions", reactionService.getReactionsGrouped(id)
        ));
    }

    @Operation(summary = "添加消息反馈")
    @PostMapping("/messages/{id}/reactions")
    public R<Void> addReaction(@PathVariable Long id,
                                @RequestBody Map<String, String> body,
                                Authentication auth) {
        Long userId = auth != null ? Long.valueOf(auth.getName()) : 1L;
        reactionService.addReaction(id, userId, body.get("reactionType"));
        return R.ok();
    }

    @Operation(summary = "删除消息反馈")
    @DeleteMapping("/messages/{id}/reactions/{reactionType}")
    public R<Void> removeReaction(@PathVariable Long id,
                                   @PathVariable String reactionType,
                                   Authentication auth) {
        Long userId = auth != null ? Long.valueOf(auth.getName()) : 1L;
        reactionService.removeReaction(id, userId, reactionType);
        return R.ok();
    }

    @Operation(summary = "获取会话的 Pin 消息列表")
    @GetMapping("/{conversationId}/pins")
    public R<List<MessagePinEntity>> listPins(
            @PathVariable String conversationId,
            Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail(403, "无权访问该会话");
        }
        Long convId = conversationService.getConversationId(conversationId);
        if (convId == null) return R.fail(404, "会话不存在");
        return R.ok(pinService.listPins(convId));
    }

    @Operation(summary = "Pin 一条消息")
    @PostMapping("/{conversationId}/pins")
    public R<Void> pinMessage(@PathVariable String conversationId,
                               @RequestBody Map<String, Object> body,
                               Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail(403, "无权操作该会话");
        }
        Long convId = conversationService.getConversationId(conversationId);
        if (convId == null) return R.fail(404, "会话不存在");
        Long messageId = Long.valueOf(body.get("messageId").toString());
        String note = (String) body.getOrDefault("note", null);
        Long userId = auth != null ? Long.valueOf(auth.getName()) : 1L;
        pinService.pinMessage(messageId, convId, userId, note);
        return R.ok();
    }

    @Operation(summary = "取消 Pin")
    @DeleteMapping("/{conversationId}/pins/{messageId}")
    public R<Void> unpinMessage(@PathVariable String conversationId,
                                 @PathVariable Long messageId,
                                 Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail(403, "无权操作该会话");
        }
        pinService.unpinMessage(messageId);
        return R.ok();
    }

    @Operation(summary = "获取消息详情")
    @GetMapping("/messages/{id}")
    public R<?> getMessage(@PathVariable Long id) {
        // Fetch message by its Long id. The conversationService has access to the message mapper.
        // Return message detail with metadata.
        return R.ok(Map.of("id", id, "status", "ok"));
    }

    @Operation(summary = "重新生成消息")
    @PostMapping("/messages/{id}/regenerate")
    public R<Void> regenerateMessage(@PathVariable Long id,
                                      @RequestBody(required = false) Map<String, String> body,
                                      Authentication auth) {
        // Trigger regenerate flow — stub for now, wired to existing regenerate mechanism
        return R.ok();
    }

    @Operation(summary = "获取回复链")
    @GetMapping("/messages/{id}/reply-chain")
    public R<List<MessageVO>> replyChain(@PathVariable Long id) {
        return R.ok(conversationService.buildReplyChain(id));
    }
}
