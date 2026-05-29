package vip.mate.orchestrator.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("mate_orchestrator_task")
public class OrchestratorTaskEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long conversationId;
    private Long messageId;
    private String title;
    @TableField(value = "plan_json", updateStrategy = FieldStrategy.ALWAYS)
    private String planJson;
    private String status;
    private Integer totalAssignments;
    private Integer completedAssignments;
    private Integer failedAssignments;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long aggregationMessageId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
