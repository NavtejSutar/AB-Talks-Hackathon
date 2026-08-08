-- V2__add_post_fields.sql: Extend posts table with structured fields for editorial pipeline

ALTER TABLE posts
  ADD COLUMN IF NOT EXISTS headline          TEXT,
  ADD COLUMN IF NOT EXISTS body              TEXT,
  ADD COLUMN IF NOT EXISTS hashtags          TEXT ARRAY,
  ADD COLUMN IF NOT EXISTS credibility_tier  TEXT;

-- Back-fill: copy existing text into headline for legacy rows
UPDATE posts SET headline = LEFT(text, 255) WHERE headline IS NULL;