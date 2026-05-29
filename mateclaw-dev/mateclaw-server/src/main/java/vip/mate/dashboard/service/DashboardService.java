package vip.mate.dashboard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Dashboard 统计服务
 * <p>
 * 直接实时查询 mate_message / mate_conversation 表，不依赖预聚合。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;

    /**
     * 获取概览统计（今日/本周/本月）— 实时查询
     */
    public Map<String, Object> getOverview(Long workspaceId) {
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1);
        LocalDate monthStart = today.withDayOfMonth(1);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("today", queryStats(workspaceId, today, today));
        result.put("thisWeek", queryStats(workspaceId, weekStart, today));
        result.put("thisMonth", queryStats(workspaceId, monthStart, today));
        return result;
    }

    /**
     * 获取日趋势数据（最近 N 天，按天聚合）
     */
    public List<Map<String, Object>> getTrend(Long workspaceId, int days) {
        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            Map<String, Object> dayStats = queryStats(workspaceId, date, date);
            dayStats.put("date", date.toString());
            trend.add(dayStats);
        }
        return trend;
    }

    private Map<String, Object> queryStats(Long workspaceId, LocalDate startDate, LocalDate endDate) {
        LocalDateTime startTime = startDate.atStartOfDay();
        LocalDateTime endTime = endDate.atTime(LocalTime.MAX);

        // 对话数
        LambdaQueryWrapper<ConversationEntity> convWrapper = new LambdaQueryWrapper<ConversationEntity>()
                .ge(ConversationEntity::getCreateTime, startTime)
                .le(ConversationEntity::getCreateTime, endTime);
        if (workspaceId != null) {
            convWrapper.eq(ConversationEntity::getWorkspaceId, workspaceId);
        }
        long conversations = conversationMapper.selectCount(convWrapper);

        // Workspace 级消息过滤：通过 conversation 关联 workspace
        // MessageEntity 没有 workspaceId 字段，需通过所属 conversation 间接过滤
        List<String> wsConversationIds = null;
        if (workspaceId != null) {
            List<ConversationEntity> wsConvs = conversationMapper.selectList(
                    new LambdaQueryWrapper<ConversationEntity>()
                            .eq(ConversationEntity::getWorkspaceId, workspaceId)
                            .select(ConversationEntity::getConversationId));
            wsConversationIds = wsConvs.stream()
                    .map(ConversationEntity::getConversationId).toList();
            if (wsConversationIds.isEmpty()) {
                // 该 workspace 无任何对话，直接返回零值
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("conversations", conversations);
                empty.put("messages", 0L);
                empty.put("totalTokens", 0L);
                empty.put("promptTokens", 0L);
                empty.put("completionTokens", 0L);
                empty.put("toolCalls", 0L);
                return empty;
            }
        }

        // 总消息数
        LambdaQueryWrapper<MessageEntity> msgWrapper = new LambdaQueryWrapper<MessageEntity>()
                .ge(MessageEntity::getCreateTime, startTime)
                .le(MessageEntity::getCreateTime, endTime)
                .eq(MessageEntity::getDeleted, 0);
        if (wsConversationIds != null) {
            msgWrapper.in(MessageEntity::getConversationId, wsConversationIds);
        }
        long messages = messageMapper.selectCount(msgWrapper);

        // Token 统计（assistant 消息）
        LambdaQueryWrapper<MessageEntity> tokenWrapper = new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getRole, "assistant")
                .ge(MessageEntity::getCreateTime, startTime)
                .le(MessageEntity::getCreateTime, endTime)
                .eq(MessageEntity::getDeleted, 0)
                .select(MessageEntity::getPromptTokens, MessageEntity::getCompletionTokens);
        if (wsConversationIds != null) {
            tokenWrapper.in(MessageEntity::getConversationId, wsConversationIds);
        }

        List<MessageEntity> assistantMessages = messageMapper.selectList(tokenWrapper);

        long totalTokens = 0, promptTokens = 0, completionTokens = 0;
        for (MessageEntity m : assistantMessages) {
            int pt = m.getPromptTokens() != null ? m.getPromptTokens() : 0;
            int ct = m.getCompletionTokens() != null ? m.getCompletionTokens() : 0;
            promptTokens += pt;
            completionTokens += ct;
            totalTokens += pt + ct;
        }

        // Tool 调用数（role = tool 的消息）
        LambdaQueryWrapper<MessageEntity> toolWrapper = new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getRole, "tool")
                .ge(MessageEntity::getCreateTime, startTime)
                .le(MessageEntity::getCreateTime, endTime)
                .eq(MessageEntity::getDeleted, 0);
        if (wsConversationIds != null) {
            toolWrapper.in(MessageEntity::getConversationId, wsConversationIds);
        }
        long toolCalls = messageMapper.selectCount(toolWrapper);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("conversations", conversations);
        stats.put("messages", messages);
        stats.put("totalTokens", totalTokens);
        stats.put("promptTokens", promptTokens);
        stats.put("completionTokens", completionTokens);
        stats.put("toolCalls", toolCalls);
        return stats;
    }
}
