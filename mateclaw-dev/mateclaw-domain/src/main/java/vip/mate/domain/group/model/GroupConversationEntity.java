package vip.mate.domain.group.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 群聊会话扩展实体 — 数据库设计文档 4.1 */
@Data
@TableName("mate_group_conversation")
public class GroupConversationEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** FK 关联 mate_conversation.id */
    private Long conversationId;

    /** FK 关联 mate_agent.id */
    private Long orchestratorAgentId;

    /** auto / manual */
    private String schedulingMode;

    /** fail_fast / fail_tolerant */
    private String failurePolicy;

    /** 最大并行任务数 */
    private Integer maxParallelTasks;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
