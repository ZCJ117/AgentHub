CREATE TABLE IF NOT EXISTS mate_deploy_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    deploy_target VARCHAR(50) NOT NULL,
    deploy_url VARCHAR(1000),
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    error_log CLOB,
    deployed_by BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT fk_dr_artifact FOREIGN KEY (artifact_id) REFERENCES mate_artifact(id) ON DELETE CASCADE,
    CONSTRAINT fk_dr_version FOREIGN KEY (version_id) REFERENCES mate_artifact_version(id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS idx_dr_artifact ON mate_deploy_record(artifact_id);
CREATE INDEX IF NOT EXISTS idx_dr_status ON mate_deploy_record(status);
CREATE INDEX IF NOT EXISTS idx_dr_deployed_by ON mate_deploy_record(deployed_by);
