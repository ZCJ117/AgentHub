CREATE TABLE IF NOT EXISTS mate_orchestrator_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    title VARCHAR(500) NOT NULL,
    plan_json CLOB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'pending',
    total_assignments INT NOT NULL DEFAULT 0,
    completed_assignments INT NOT NULL DEFAULT 0,
    failed_assignments INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    aggregation_message_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ot_conversation FOREIGN KEY (conversation_id) REFERENCES mate_conversation(id) ON DELETE CASCADE,
    CONSTRAINT fk_ot_message FOREIGN KEY (message_id) REFERENCES mate_message(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ot_conversation ON mate_orchestrator_task(conversation_id);
CREATE INDEX IF NOT EXISTS idx_ot_status ON mate_orchestrator_task(status);
CREATE INDEX IF NOT EXISTS idx_ot_created ON mate_orchestrator_task(created_at);
