ALTER TABLE mate_group_conversation
    ALTER COLUMN orchestrator_agent_id BIGINT NULL;
ALTER TABLE mate_group_conversation
    DROP CONSTRAINT fk_gc_orchestrator;
ALTER TABLE mate_group_conversation
    ADD CONSTRAINT fk_gc_orchestrator
        FOREIGN KEY (orchestrator_agent_id) REFERENCES mate_agent(id) ON DELETE SET NULL;
