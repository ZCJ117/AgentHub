ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS conversation_type VARCHAR(20) DEFAULT 'direct';
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE;
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS pinned_at TIMESTAMP;
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS last_active_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS last_message_preview VARCHAR(200);
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS unread_count INT DEFAULT 0;
UPDATE mate_conversation SET last_active_at = update_time WHERE last_active_at IS NULL;
UPDATE mate_conversation SET conversation_type = 'direct' WHERE conversation_type IS NULL;
