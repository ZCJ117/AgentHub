CREATE TABLE IF NOT EXISTS mate_group_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    orchestrator_agent_id BIGINT NOT NULL,
    scheduling_mode VARCHAR(20) NOT NULL DEFAULT 'auto',
    failure_policy VARCHAR(30) NOT NULL DEFAULT 'fail_tolerant',
    max_parallel_tasks INT NOT NULL DEFAULT 8,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_conversation UNIQUE (conversation_id),
    CONSTRAINT fk_gc_conversation FOREIGN KEY (conversation_id) REFERENCES mate_conversation(id) ON DELETE CASCADE,
    CONSTRAINT fk_gc_orchestrator FOREIGN KEY (orchestrator_agent_id) REFERENCES mate_agent(id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS idx_orchestrator ON mate_group_conversation(orchestrator_agent_id);
