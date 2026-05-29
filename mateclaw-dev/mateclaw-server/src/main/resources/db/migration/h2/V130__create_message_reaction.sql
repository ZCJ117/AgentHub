CREATE TABLE IF NOT EXISTS mate_message_reaction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reaction_type VARCHAR(30) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_msg_user_type UNIQUE (message_id, user_id, reaction_type),
    CONSTRAINT fk_mr_message FOREIGN KEY (message_id) REFERENCES mate_message(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_mr_message ON mate_message_reaction(message_id);
