CREATE TABLE IF NOT EXISTS mate_message_reaction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reaction_type VARCHAR(30) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_msg_user_type (message_id, user_id, reaction_type),
    KEY idx_mr_message (message_id),
    CONSTRAINT fk_mr_message FOREIGN KEY (message_id) REFERENCES mate_message(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
