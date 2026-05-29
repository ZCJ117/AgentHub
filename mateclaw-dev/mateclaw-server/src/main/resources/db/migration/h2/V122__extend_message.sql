ALTER TABLE mate_message ADD COLUMN IF NOT EXISTS message_type VARCHAR(30) DEFAULT 'text';
ALTER TABLE mate_message ADD COLUMN IF NOT EXISTS reply_to_id BIGINT;
ALTER TABLE mate_message ADD COLUMN IF NOT EXISTS sender_agent_id BIGINT;
ALTER TABLE mate_message ADD COLUMN IF NOT EXISTS artifact_refs VARCHAR(2000);
ALTER TABLE mate_message ADD COLUMN IF NOT EXISTS regenerated_from_id BIGINT;
UPDATE mate_message SET message_type = 'text' WHERE message_type IS NULL;
