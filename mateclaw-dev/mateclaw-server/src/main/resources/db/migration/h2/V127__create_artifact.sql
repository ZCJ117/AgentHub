CREATE TABLE IF NOT EXISTS mate_artifact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT,
    creator_agent_id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL,
    artifact_name VARCHAR(255) NOT NULL,
    artifact_type VARCHAR(30) NOT NULL,
    file_path VARCHAR(1000),
    current_version INT NOT NULL DEFAULT 1,
    deploy_status VARCHAR(20) NOT NULL DEFAULT 'none',
    deploy_url VARCHAR(1000),
    tags VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_art_conversation FOREIGN KEY (conversation_id) REFERENCES mate_conversation(id) ON DELETE CASCADE,
    CONSTRAINT fk_art_message FOREIGN KEY (message_id) REFERENCES mate_message(id) ON DELETE SET NULL,
    CONSTRAINT fk_art_agent FOREIGN KEY (creator_agent_id) REFERENCES mate_agent(id) ON DELETE RESTRICT,
    CONSTRAINT fk_art_workspace FOREIGN KEY (workspace_id) REFERENCES mate_workspace(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_art_conversation ON mate_artifact(conversation_id);
CREATE INDEX IF NOT EXISTS idx_art_creator ON mate_artifact(creator_agent_id);
CREATE INDEX IF NOT EXISTS idx_art_workspace ON mate_artifact(workspace_id);
CREATE INDEX IF NOT EXISTS idx_art_type ON mate_artifact(artifact_type);
CREATE INDEX IF NOT EXISTS idx_art_deploy ON mate_artifact(deploy_status);
CREATE INDEX IF NOT EXISTS idx_art_created ON mate_artifact(created_at);
