package vip.mate.domain.workspace.conversation.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话实体
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_conversation")
public class ConversationEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 会话唯一标识（前端生成的UUID） */
    private String conversationId;

    /** 会话标题（取第一条消息前20字） */
    private String title;

    /** 关联的 Agent ID */
    private Long agentId;

    /** 创建用户 */
    private String username;

    /** 消息数量 */
    private Integer messageCount;

    /** 最后一条消息摘要 */
    @TableField(value = "last_message", updateStrategy = FieldStrategy.ALWAYS)
    private String lastMessage;

    /** 最后活跃时间 */
    private LocalDateTime lastActiveTime;

    /** 流状态：idle（空闲）/ running（生成中） */
    private String streamStatus;

    /** 所属工作区 ID（默认 1 = default） */
    private Long workspaceId;

    /** 父会话 ID（委派场景下，子会话记录其父会话的 conversationId） */
    private String parentConversationId;

    /** Pin flag: 0 = normal, 1 = pinned to the top of the sidebar list */
    private Integer pinned;

    /**
     * Provider id of the model this conversation is pinned to. NULL means
     * "inherit" — fall back to the agent's model override, then the global
     * default. Paired with {@link #modelName}.
     */
    private String modelProvider;

    /** Model id this conversation is pinned to. See {@link #modelProvider}. */
    private String modelName;

    /** 会话类型：direct（单聊）/ group（群聊） — 数据库设计文档 3.2 */
    private String conversationType;

    /** 是否已归档 — 数据库设计文档 3.2 */
    private Integer archived;

    /** 置顶时间，NULL 表示未置顶 — 数据库设计文档 3.2 */
    private LocalDateTime pinnedAt;

    /** 最近活跃时间，用于排序 — 数据库设计文档 3.2 */
    private LocalDateTime lastActiveAt;

    /** 最后一条消息摘要 — 数据库设计文档 3.2 */
    private String lastMessagePreview;

    /** 当前用户未读消息计数 — 数据库设计文档 3.2 */
    private Integer unreadCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
