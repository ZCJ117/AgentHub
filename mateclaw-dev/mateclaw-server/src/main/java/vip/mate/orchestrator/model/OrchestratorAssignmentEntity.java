package vip.mate.orchestrator.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("mate_orchestrator_assignment")
public class OrchestratorAssignmentEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long taskId;
    private Long agentId;
    private Integer stepOrder;
    private String executionMode;
    @TableField(value = "goal", updateStrategy = FieldStrategy.ALWAYS)
    private String goal;
    private Long dependencyOn;
    private String status;
    private Long childConversationId;
    private String resultSummary;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
