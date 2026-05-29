CREATE TABLE IF NOT EXISTS mate_artifact_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    message_id BIGINT,
    change_summary VARCHAR(500),
    file_path VARCHAR(1000) NOT NULL,
    content_hash VARCHAR(64),
    diff_from_prev CLOB,
    tag VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_artifact_version UNIQUE (artifact_id, version_number),
    CONSTRAINT fk_av_artifact FOREIGN KEY (artifact_id) REFERENCES mate_artifact(id) ON DELETE CASCADE,
    CONSTRAINT fk_av_message FOREIGN KEY (message_id) REFERENCES mate_message(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_av_message ON mate_artifact_version(message_id);
CREATE INDEX IF NOT EXISTS idx_av_tag ON mate_artifact_version(tag);
