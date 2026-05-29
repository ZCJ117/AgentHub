CREATE TABLE IF NOT EXISTS mate_message_pin (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    pinned_by BIGINT NOT NULL,
    note VARCHAR(500),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message (message_id),
    KEY idx_mp_conversation (conversation_id),
    CONSTRAINT fk_mp_message FOREIGN KEY (message_id) REFERENCES mate_message(id) ON DELETE CASCADE,
    CONSTRAINT fk_mp_conversation FOREIGN KEY (conversation_id) REFERENCES mate_conversation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
