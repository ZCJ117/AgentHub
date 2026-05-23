package vip.mate.artifact.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.artifact.model.*;
import vip.mate.artifact.repository.*;
import vip.mate.artifact.storage.ArtifactStorageProvider;
import vip.mate.exception.MateClawException;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactService {

    private final ArtifactMapper artifactMapper;
    private final ArtifactVersionMapper versionMapper;
    private final ArtifactStorageProvider storageProvider;

    @Transactional
    public ArtifactEntity createArtifact(Long conversationId, Long messageId,
                                          Long creatorAgentId, Long workspaceId,
                                          String name, String type, InputStream content,
                                          String filename) {
        ArtifactEntity artifact = new ArtifactEntity();
        artifact.setConversationId(conversationId);
        artifact.setMessageId(messageId);
        artifact.setCreatorAgentId(creatorAgentId);
        artifact.setWorkspaceId(workspaceId != null ? workspaceId : 1L);
        artifact.setArtifactName(name);
        artifact.setArtifactType(type != null ? type : "other");
        artifact.setCurrentVersion(1);
        artifact.setDeployStatus("none");
        artifactMapper.insert(artifact);

        String path = storageProvider.store(artifact.getId(), 1, filename, content);
        String hash = computeHash(path);

        ArtifactVersionEntity v = new ArtifactVersionEntity();
        v.setArtifactId(artifact.getId());
        v.setVersionNumber(1);
        v.setMessageId(messageId);
        v.setFilePath(path);
        v.setContentHash(hash);
        v.setChangeSummary("Initial version");
        versionMapper.insert(v);

        artifact.setFilePath(path);
        artifactMapper.updateById(artifact);
        return artifact;
    }

    public ArtifactEntity getArtifact(Long id) {
        ArtifactEntity artifact = artifactMapper.selectById(id);
        if (artifact == null) throw new MateClawException("err.artifact.not_found", "产物不存在");
        return artifact;
    }

    public ArtifactContentVO getContent(Long artifactId) {
        ArtifactEntity artifact = getArtifact(artifactId);
        ArtifactVersionEntity latestVersion = versionMapper.selectOne(
            new LambdaQueryWrapper<ArtifactVersionEntity>()
                .eq(ArtifactVersionEntity::getArtifactId, artifactId)
                .eq(ArtifactVersionEntity::getVersionNumber, artifact.getCurrentVersion()));
        if (latestVersion == null) {
            throw new MateClawException("err.artifact.version_not_found", "版本不存在");
        }
        return buildContentVO(artifact, latestVersion);
    }

    public ArtifactContentVO getVersionContent(Long artifactId, Long versionId) {
        ArtifactEntity artifact = getArtifact(artifactId);
        ArtifactVersionEntity version = getVersion(artifactId, versionId);
        return buildContentVO(artifact, version);
    }

    private ArtifactContentVO buildContentVO(ArtifactEntity artifact, ArtifactVersionEntity version) {
        String fileName = extractFileName(version.getFilePath());
        String contentType = guessContentType(fileName, artifact.getArtifactType());

        String content = null;
        String downloadUrl = null;

        if (isTextType(contentType)) {
            try {
                content = new String(storageProvider.read(version.getFilePath()).readAllBytes());
            } catch (Exception e) {
                log.warn("Failed to read artifact content: {}", e.getMessage());
            }
        } else {
            downloadUrl = "/api/v1/artifacts/" + artifact.getId() + "/versions/" + version.getId() + "/raw";
        }

        return ArtifactContentVO.builder()
            .content(content)
            .contentType(contentType)
            .fileName(fileName)
            .downloadUrl(downloadUrl)
            .build();
    }

    private String extractFileName(String filePath) {
        if (filePath == null) return "untitled";
        int idx = filePath.lastIndexOf('/');
        return idx >= 0 ? filePath.substring(idx + 1) : filePath;
    }

    private String guessContentType(String fileName, String artifactType) {
        if ("html".equalsIgnoreCase(artifactType)) return "text/html";
        if ("markdown".equalsIgnoreCase(artifactType)) return "text/markdown";
        if ("code".equalsIgnoreCase(artifactType)) {
            String ext = fileName != null && fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "";
            return switch (ext) {
                case "js" -> "text/javascript";
                case "ts" -> "text/typescript";
                case "py" -> "text/x-python";
                case "java" -> "text/x-java";
                case "go" -> "text/x-go";
                case "rs" -> "text/x-rust";
                case "css" -> "text/css";
                case "json" -> "application/json";
                case "yaml", "yml" -> "text/x-yaml";
                case "sql" -> "text/x-sql";
                case "sh" -> "text/x-sh";
                case "xml" -> "text/xml";
                default -> "text/plain";
            };
        }
        if ("image".equalsIgnoreCase(artifactType)) {
            String ext = fileName != null && fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "png";
            return "image/" + ext;
        }
        return "application/octet-stream";
    }

    private boolean isTextType(String contentType) {
        return contentType.startsWith("text/") || contentType.equals("application/json")
            || contentType.equals("application/javascript");
    }

    public Map<String, Object> listArtifacts(Long conversationId, String type,
                                              String deployStatus, int page, int size) {
        Page<ArtifactEntity> pageReq = new Page<>(page, size);
        LambdaQueryWrapper<ArtifactEntity> qw = new LambdaQueryWrapper<>();
        if (conversationId != null) qw.eq(ArtifactEntity::getConversationId, conversationId);
        if (type != null && !type.isBlank()) qw.eq(ArtifactEntity::getArtifactType, type);
        if (deployStatus != null && !deployStatus.isBlank()) qw.eq(ArtifactEntity::getDeployStatus, deployStatus);
        qw.orderByDesc(ArtifactEntity::getUpdatedAt);

        IPage<ArtifactEntity> result = artifactMapper.selectPage(pageReq, qw);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("records", result.getRecords());
        map.put("total", result.getTotal());
        map.put("current", result.getCurrent());
        map.put("size", result.getSize());
        map.put("pages", result.getPages());
        return map;
    }

    public List<ArtifactVersionEntity> listVersions(Long artifactId) {
        return versionMapper.selectList(
            new LambdaQueryWrapper<ArtifactVersionEntity>()
                .eq(ArtifactVersionEntity::getArtifactId, artifactId)
                .orderByDesc(ArtifactVersionEntity::getVersionNumber));
    }

    public ArtifactVersionEntity getVersion(Long artifactId, Long versionId) {
        ArtifactVersionEntity v = versionMapper.selectById(versionId);
        if (v == null || !v.getArtifactId().equals(artifactId)) {
            throw new MateClawException("err.artifact.version_not_found", "版本不存在");
        }
        return v;
    }

    @Transactional
    public ArtifactEntity restoreVersion(Long artifactId, Long versionId) {
        ArtifactEntity artifact = getArtifact(artifactId);
        ArtifactVersionEntity targetVersion = getVersion(artifactId, versionId);

        int newVersion = artifact.getCurrentVersion() + 1;

        ArtifactVersionEntity newV = new ArtifactVersionEntity();
        newV.setArtifactId(artifactId);
        newV.setVersionNumber(newVersion);
        newV.setFilePath(targetVersion.getFilePath());
        newV.setContentHash(targetVersion.getContentHash());
        newV.setChangeSummary("Restored from version " + targetVersion.getVersionNumber());
        versionMapper.insert(newV);

        artifact.setCurrentVersion(newVersion);
        artifact.setFilePath(targetVersion.getFilePath());
        artifactMapper.updateById(artifact);
        return artifact;
    }

    @Transactional
    public void updateTags(Long artifactId, List<String> tags) {
        ArtifactEntity artifact = getArtifact(artifactId);
        artifact.setTags(tags != null ? String.join(",", tags) : null);
        artifactMapper.updateById(artifact);
    }

    public Map<String, Object> computeDiff(Long artifactId, int fromVersion, int toVersion) {
        ArtifactVersionEntity from = versionMapper.selectOne(
            new LambdaQueryWrapper<ArtifactVersionEntity>()
                .eq(ArtifactVersionEntity::getArtifactId, artifactId)
                .eq(ArtifactVersionEntity::getVersionNumber, fromVersion));
        ArtifactVersionEntity to = versionMapper.selectOne(
            new LambdaQueryWrapper<ArtifactVersionEntity>()
                .eq(ArtifactVersionEntity::getArtifactId, artifactId)
                .eq(ArtifactVersionEntity::getVersionNumber, toVersion));
        if (from == null || to == null) throw new MateClawException("err.artifact.version_not_found", "版本不存在");

        String diff;
        try {
            String fromContent = from.getFilePath() != null
                ? new String(storageProvider.read(from.getFilePath()).readAllBytes()) : "";
            String toContent = to.getFilePath() != null
                ? new String(storageProvider.read(to.getFilePath()).readAllBytes()) : "";
            diff = simpleDiff(fromContent, toContent);
        } catch (Exception e) {
            diff = to.getDiffFromPrev() != null ? to.getDiffFromPrev() : "";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifactId", artifactId);
        result.put("fromVersion", fromVersion);
        result.put("toVersion", toVersion);
        result.put("diff", diff);
        return result;
    }

    private String computeHash(String filePath) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] content = storageProvider.read(filePath).readAllBytes();
            byte[] hash = md.digest(content);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            log.warn("Hash computation failed", e);
            return null;
        }
    }

    private String simpleDiff(String a, String b) {
        if (a.equals(b)) return "";
        String[] aLines = a.split("\n", -1);
        String[] bLines = b.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int max = Math.max(aLines.length, bLines.length);
        for (int i = 0; i < max; i++) {
            if (i < aLines.length && i < bLines.length && aLines[i].equals(bLines[i])) {
                sb.append("  ").append(bLines[i]).append("\n");
            } else {
                if (i < aLines.length) sb.append("- ").append(aLines[i]).append("\n");
                if (i < bLines.length) sb.append("+ ").append(bLines[i]).append("\n");
            }
        }
        return sb.toString();
    }
}
