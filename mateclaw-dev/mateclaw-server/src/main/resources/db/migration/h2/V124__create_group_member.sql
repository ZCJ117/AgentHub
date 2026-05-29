CREATE TABLE IF NOT EXISTS mate_group_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    member_role VARCHAR(20) NOT NULL DEFAULT 'member',
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_conversation_agent UNIQUE (conversation_id, agent_id),
    CONSTRAINT fk_gm_conversation FOREIGN KEY (conversation_id) REFERENCES mate_conversation(id) ON DELETE CASCADE,
    CONSTRAINT fk_gm_agent FOREIGN KEY (agent_id) REFERENCES mate_agent(id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS idx_agent ON mate_group_member(agent_id);
