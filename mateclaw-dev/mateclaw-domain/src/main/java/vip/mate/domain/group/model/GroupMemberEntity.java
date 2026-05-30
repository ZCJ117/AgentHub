package vip.mate.domain.group.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 群聊成员实体 — 数据库设计文档 4.2 */
@Data
@TableName("mate_group_member")
public class GroupMemberEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** FK 关联 mate_conversation.id */
    private Long conversationId;

    /** FK 关联 mate_agent.id */
    private Long agentId;

    /** orchestrator / member */
    private String memberRole;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime joinedAt;
}
