package vip.mate.artifact.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.artifact.model.*;
import vip.mate.artifact.service.*;
import vip.mate.common.result.R;

import java.util.List;
import java.util.Map;

@Tag(name = "产物管理")
@RestController
@RequestMapping("/api/v1/artifacts")
@RequiredArgsConstructor
public class ArtifactController {

    private final ArtifactService artifactService;
    private final ArtifactDeployService deployService;

    @Operation(summary = "获取产物列表")
    @GetMapping
    public R<Map<String, Object>> list(
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String deployStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(artifactService.listArtifacts(conversationId, type, deployStatus, page, size));
    }

    @Operation(summary = "获取产物详情")
    @GetMapping("/{id}")
    public R<ArtifactEntity> detail(@PathVariable Long id) {
        return R.ok(artifactService.getArtifact(id));
    }

    @Operation(summary = "获取版本历史")
    @GetMapping("/{id}/versions")
    public R<List<ArtifactVersionEntity>> versions(@PathVariable Long id) {
        return R.ok(artifactService.listVersions(id));
    }

    @Operation(summary = "获取指定版本详情")
    @GetMapping("/{id}/versions/{versionId}")
    public R<ArtifactVersionEntity> version(@PathVariable Long id, @PathVariable Long versionId) {
        return R.ok(artifactService.getVersion(id, versionId));
    }

    @Operation(summary = "获取产物最新版本内容")
    @GetMapping("/{id}/content")
    public R<ArtifactContentVO> content(@PathVariable Long id) {
        return R.ok(artifactService.getContent(id));
    }

    @Operation(summary = "获取产物指定版本内容")
    @GetMapping("/{id}/versions/{versionId}/content")
    public R<ArtifactContentVO> versionContent(@PathVariable Long id, @PathVariable Long versionId) {
        return R.ok(artifactService.getVersionContent(id, versionId));
    }

    @Operation(summary = "版本差异对比")
    @GetMapping("/{id}/versions/diff")
    public R<Map<String, Object>> diff(@PathVariable Long id,
                                        @RequestParam int from,
                                        @RequestParam int to) {
        return R.ok(artifactService.computeDiff(id, from, to));
    }

    @Operation(summary = "回滚到指定版本")
    @PostMapping("/{id}/versions/{versionId}/restore")
    public R<ArtifactEntity> restore(@PathVariable Long id, @PathVariable Long versionId) {
        return R.ok(artifactService.restoreVersion(id, versionId));
    }

    @Operation(summary = "更新产物标签")
    @PutMapping("/{id}/tags")
    public R<Void> updateTags(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) body.get("tags");
        artifactService.updateTags(id, tags);
        return R.ok();
    }

    @Operation(summary = "部署产物")
    @PostMapping("/{id}/deploy")
    public R<DeployRecordEntity> deploy(@PathVariable Long id,
                                         @RequestBody Map<String, Object> body,
                                         Authentication auth) {
        Long versionId = body.containsKey("versionId")
            ? Long.valueOf(body.get("versionId").toString()) : null;
        String target = (String) body.getOrDefault("deployTarget", "local");
        Long userId = auth != null ? Long.valueOf(auth.getName()) : 1L;
        return R.ok(deployService.deploy(id, versionId, target, userId));
    }

    @Operation(summary = "查询部署状态")
    @GetMapping("/{id}/deploy/status")
    public R<DeployRecordEntity> deployStatus(@PathVariable Long id) {
        return R.ok(deployService.getDeployStatus(id));
    }

    @Operation(summary = "查询部署历史")
    @GetMapping("/{id}/deploy/history")
    public R<List<DeployRecordEntity>> deployHistory(@PathVariable Long id,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return R.ok(deployService.getDeployHistory(id, page, size));
    }
}
