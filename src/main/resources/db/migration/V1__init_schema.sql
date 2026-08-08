-- V1__init_schema.sql: Initial schema for Wren Autonomous AI Security Researcher Agent System

-- Agents: single row per initialized persona instance
CREATE TABLE agents (
  id                UUID PRIMARY KEY,
  persona_name      TEXT NOT NULL,
  persona_domain    TEXT NOT NULL,
  status            TEXT NOT NULL DEFAULT 'ACTIVE',
  post_sequence     INT NOT NULL DEFAULT 0,
  initialized_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  last_tick_at      TIMESTAMP WITH TIME ZONE,
  next_tick_at      TIMESTAMP WITH TIME ZONE
);

-- Posts: feed output and internal metadata
CREATE TABLE posts (
  id                    TEXT PRIMARY KEY,
  agent_id              UUID NOT NULL REFERENCES agents(id),
  created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  text                  TEXT NOT NULL,
  rationale             TEXT NOT NULL,
  sources               TEXT ARRAY NOT NULL,
  topic_key             TEXT NOT NULL,
  is_followup_of        TEXT REFERENCES posts(id),
  confidence            DOUBLE PRECISION,
  editorial_score       DOUBLE PRECISION,
  llm_provider_used     TEXT,
  self_critique_verdict TEXT
);
CREATE INDEX idx_posts_agent_created ON posts(agent_id, created_at DESC);
CREATE INDEX idx_posts_topic_key ON posts(topic_key);

-- Topic Candidates: receipts trail for editorial decision making
CREATE TABLE topic_candidates (
  id                       UUID PRIMARY KEY,
  agent_id                 UUID NOT NULL REFERENCES agents(id),
  tick_id                  UUID NOT NULL,
  discovered_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  source                   TEXT NOT NULL,
  raw_title                TEXT NOT NULL,
  raw_url                  TEXT NOT NULL,
  credibility_tier         TEXT,
  editorial_score          DOUBLE PRECISION,
  confidence               DOUBLE PRECISION,
  persona_alignment_passed BOOLEAN,
  decision                 TEXT NOT NULL,
  decision_reason          TEXT NOT NULL,
  decision_stage           TEXT NOT NULL,
  resulted_post_id         TEXT REFERENCES posts(id)
);
CREATE INDEX idx_candidates_agent_tick ON topic_candidates(agent_id, tick_id);

-- Memory Entries: RAG retrieval context substrate
CREATE TABLE memory_entries (
  id              UUID PRIMARY KEY,
  agent_id        UUID NOT NULL REFERENCES agents(id),
  post_id         TEXT REFERENCES posts(id),
  topic_key       TEXT NOT NULL,
  summary         TEXT NOT NULL,
  opinion_stance  TEXT,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_memory_agent_topic ON memory_entries(agent_id, topic_key);

-- Pipeline Metrics: observability and execution logs per tick
CREATE TABLE pipeline_metrics (
  id                       UUID PRIMARY KEY,
  agent_id                 UUID NOT NULL REFERENCES agents(id),
  tick_id                  UUID NOT NULL UNIQUE,
  tick_started_at          TIMESTAMP WITH TIME ZONE NOT NULL,
  tick_completed_at        TIMESTAMP WITH TIME ZONE,
  candidates_discovered    INT,
  candidates_rejected      INT,
  candidates_accepted      INT,
  avg_editorial_score      DOUBLE PRECISION,
  llm_provider_used        TEXT,
  llm_provider_failovers   INT DEFAULT 0,
  llm_latency_ms           INT,
  api_failures             INT DEFAULT 0,
  self_critique_revisions  INT DEFAULT 0,
  self_critique_rejections INT DEFAULT 0,
  resulted_post_id         TEXT REFERENCES posts(id)
);
