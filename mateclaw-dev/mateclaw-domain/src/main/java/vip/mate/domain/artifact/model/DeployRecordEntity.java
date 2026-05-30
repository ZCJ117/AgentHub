package vip.mate.domain.artifact.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("mate_deploy_record")
public class DeployRecordEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long artifactId;
    private Long versionId;
    private String deployTarget;
    private String deployUrl;
    private String status;
    @TableField(value = "error_log", updateStrategy = FieldStrategy.ALWAYS)
    private String errorLog;
    private Long deployedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
