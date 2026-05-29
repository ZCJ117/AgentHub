CREATE TABLE IF NOT EXISTS mate_orchestrator_assignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    step_order INT NOT NULL,
    execution_mode VARCHAR(20) NOT NULL DEFAULT 'sequential',
    goal CLOB NOT NULL,
    dependency_on BIGINT,
    status VARCHAR(30) NOT NULL DEFAULT 'pending',
    child_conversation_id BIGINT,
    result_summary VARCHAR(2000),
    error_message VARCHAR(2000),
    retry_count INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_oa_task FOREIGN KEY (task_id) REFERENCES mate_orchestrator_task(id) ON DELETE CASCADE,
    CONSTRAINT fk_oa_agent FOREIGN KEY (agent_id) REFERENCES mate_agent(id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS idx_oa_task ON mate_orchestrator_assignment(task_id);
CREATE INDEX IF NOT EXISTS idx_oa_agent ON mate_orchestrator_assignment(agent_id);
CREATE INDEX IF NOT EXISTS idx_oa_status ON mate_orchestrator_assignment(status);
CREATE INDEX IF NOT EXISTS idx_oa_child_conv ON mate_orchestrator_assignment(child_conversation_id);
