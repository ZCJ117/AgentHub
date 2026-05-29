package vip.mate.workspace.conversation.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息实体
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_message")
public class MessageEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 会话唯一标识 */
    private String conversationId;

    /** 消息角色：user / assistant / system / tool */
    private String role;

    /** 消息内容 */
    @TableField(value = "content", updateStrategy = FieldStrategy.ALWAYS)
    private String content;

    /** 结构化内容片段（JSON） */
    @TableField(value = "content_parts", updateStrategy = FieldStrategy.ALWAYS)
    private String contentParts;

    /** 工具调用名称（role=tool 时使用） */
    private String toolName;

    /** Token 使用量 */
    private Integer tokenUsage;

    /** Prompt tokens 消耗 */
    private Integer promptTokens;

    /** Completion tokens 消耗 */
    private Integer completionTokens;

    /** 运行时模型名称 */
    private String runtimeModel;

    /** 运行时 Provider ID */
    private String runtimeProvider;

    /** 消息状态：generating / completed / stopped / failed */
    private String status;

    /** 消息类型：text / code / diff / image / file / preview_card / plan_card / system — 数据库设计文档 3.3 */
    private String messageType;

    /** 引用的消息 ID — 数据库设计文档 3.3 */
    private Long replyToId;

    /** 发送消息的 Agent ID（Agent 消息非空，用户消息为空） — 数据库设计文档 3.3 */
    private Long senderAgentId;

    /** 关联的产物 ID JSON 数组 — 数据库设计文档 3.3 */
    private String artifactRefs;

    /** 重新生成时指向原始消息 — 数据库设计文档 3.3 */
    private Long regeneratedFromId;

    /** Agent 事件元数据（JSON）：toolCalls, plan, currentPhase, pendingApproval 等 */
    @TableField(value = "metadata", updateStrategy = FieldStrategy.ALWAYS)
    private String metadata;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
