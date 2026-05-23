package vip.mate.artifact.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactContentVO {
    private String content;
    private String contentType;
    private String fileName;
    private String downloadUrl;
}
