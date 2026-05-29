package vip.mate.artifact.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("mate_artifact")
public class ArtifactEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long conversationId;
    private Long messageId;
    private Long creatorAgentId;
    private Long workspaceId;
    private String artifactName;
    private String artifactType;
    private String filePath;
    private Integer currentVersion;
    private String deployStatus;
    private String deployUrl;
    private String tags;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
