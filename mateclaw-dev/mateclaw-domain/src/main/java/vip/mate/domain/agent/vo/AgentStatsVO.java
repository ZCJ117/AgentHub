package vip.mate.domain.agent.vo;

import lombok.Builder;
import lombok.Data;

/** Agent 使用统计 VO — API 文档 4. Agent Stats */
@Data
@Builder
public class AgentStatsVO {
    private Long agentId;
    private Long totalConversations;
    private Long totalMessages;
    private Long totalTokens;
    private Long avgResponseTimeMs;
    private String lastActiveAt;
}
