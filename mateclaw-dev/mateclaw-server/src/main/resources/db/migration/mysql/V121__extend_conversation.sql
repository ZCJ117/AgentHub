ALTER TABLE mate_conversation ADD COLUMN conversation_type VARCHAR(20) DEFAULT 'direct';
ALTER TABLE mate_conversation ADD COLUMN archived TINYINT(1) DEFAULT 0;
ALTER TABLE mate_conversation ADD COLUMN pinned_at DATETIME;
ALTER TABLE mate_conversation ADD COLUMN last_active_at DATETIME DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE mate_conversation ADD COLUMN last_message_preview VARCHAR(200);
ALTER TABLE mate_conversation ADD COLUMN unread_count INT DEFAULT 0;
UPDATE mate_conversation SET last_active_at = update_time WHERE last_active_at IS NULL;
UPDATE mate_conversation SET conversation_type = 'direct' WHERE conversation_type IS NULL;
