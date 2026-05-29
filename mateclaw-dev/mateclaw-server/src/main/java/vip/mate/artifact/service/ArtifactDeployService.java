package vip.mate.artifact.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.artifact.model.*;
import vip.mate.artifact.repository.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 产物部署服务 — 当前 Noop 实现，接口预留 Vercel/static hosting 接入点
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactDeployService {

    private final DeployRecordMapper deployRecordMapper;
    private final ArtifactMapper artifactMapper;

    public DeployRecordEntity deploy(Long artifactId, Long versionId, String deployTarget, Long userId) {
        DeployRecordEntity record = new DeployRecordEntity();
        record.setArtifactId(artifactId);
        record.setVersionId(versionId);
        record.setDeployTarget(deployTarget != null ? deployTarget : "local");
        record.setStatus("deployed");
        record.setDeployUrl("/api/v1/artifacts/" + artifactId + "/preview");
        record.setDeployedBy(userId);
        record.setCreatedAt(LocalDateTime.now());
        record.setCompletedAt(LocalDateTime.now());
        deployRecordMapper.insert(record);

        artifactMapper.update(null,
            new LambdaUpdateWrapper<ArtifactEntity>()
                .eq(ArtifactEntity::getId, artifactId)
                .set(ArtifactEntity::getDeployStatus, "deployed")
                .set(ArtifactEntity::getDeployUrl, record.getDeployUrl()));

        log.info("Artifact {} deployed (noop) → {}", artifactId, record.getDeployUrl());
        return record;
    }

    public DeployRecordEntity getDeployStatus(Long artifactId) {
        return deployRecordMapper.selectOne(
            new LambdaQueryWrapper<DeployRecordEntity>()
                .eq(DeployRecordEntity::getArtifactId, artifactId)
                .orderByDesc(DeployRecordEntity::getCreatedAt)
                .last("LIMIT 1"));
    }

    public List<DeployRecordEntity> getDeployHistory(Long artifactId, int page, int size) {
        return deployRecordMapper.selectList(
            new LambdaQueryWrapper<DeployRecordEntity>()
                .eq(DeployRecordEntity::getArtifactId, artifactId)
                .orderByDesc(DeployRecordEntity::getCreatedAt)
                .last("LIMIT " + size + " OFFSET " + ((page - 1) * size)));
    }
}
