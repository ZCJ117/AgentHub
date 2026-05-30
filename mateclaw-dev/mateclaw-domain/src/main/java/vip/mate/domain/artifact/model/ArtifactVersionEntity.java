package vip.mate.domain.artifact.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("mate_artifact_version")
public class ArtifactVersionEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long artifactId;
    private Integer versionNumber;
    private Long messageId;
    private String changeSummary;
    private String filePath;
    private String contentHash;
    @TableField(value = "diff_from_prev", updateStrategy = FieldStrategy.ALWAYS)
    private String diffFromPrev;
    private String tag;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
