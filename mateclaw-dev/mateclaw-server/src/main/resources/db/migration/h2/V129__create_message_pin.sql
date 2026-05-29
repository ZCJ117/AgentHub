CREATE TABLE IF NOT EXISTS mate_message_pin (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    pinned_by BIGINT NOT NULL,
    note VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_message UNIQUE (message_id),
    CONSTRAINT fk_mp_message FOREIGN KEY (message_id) REFERENCES mate_message(id) ON DELETE CASCADE,
    CONSTRAINT fk_mp_conversation FOREIGN KEY (conversation_id) REFERENCES mate_conversation(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_mp_conversation ON mate_message_pin(conversation_id);
