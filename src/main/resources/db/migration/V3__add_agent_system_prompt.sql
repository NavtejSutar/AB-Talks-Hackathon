-- V3__add_agent_system_prompt.sql: Add system_prompt column to agents table

ALTER TABLE agents
  ADD COLUMN IF NOT EXISTS system_prompt TEXT;
