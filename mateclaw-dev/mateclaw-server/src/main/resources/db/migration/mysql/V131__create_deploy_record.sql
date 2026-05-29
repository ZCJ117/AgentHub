CREATE TABLE IF NOT EXISTS mate_deploy_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    deploy_target VARCHAR(50) NOT NULL,
    deploy_url VARCHAR(1000),
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    error_log TEXT,
    deployed_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME,
    KEY idx_dr_artifact (artifact_id),
    KEY idx_dr_status (status),
    KEY idx_dr_deployed_by (deployed_by),
    CONSTRAINT fk_dr_artifact FOREIGN KEY (artifact_id) REFERENCES mate_artifact(id) ON DELETE CASCADE,
    CONSTRAINT fk_dr_version FOREIGN KEY (version_id) REFERENCES mate_artifact_version(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
