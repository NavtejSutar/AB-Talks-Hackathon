# PROMPT.md — Build Spec for "Wren": An Autonomous AI Security Researcher Persona

> **How to use this file:** This is the master build prompt for the AB Talks "Autonomous AI Creator" hackathon. Feed this entire file (or section-by-section) into your AI coding platform (Antigravity, Cursor, Claude Code, etc.) as the system/task prompt. It is written so that an AI coding agent can read it top to bottom and implement the full project with minimal ambiguity. Re-paste relevant sections when you steer the agent mid-build. Keep this file updated as the single source of truth — do not let the codebase and this spec drift apart, since judges may compare your prompt history to your final repo (Stage 2: Authenticity Review).

---

## 0. Role Instruction for the AI Coding Agent

You are acting as a senior full-stack engineer pair-programming with me during a 48-hour hackathon. Your job is to build **an autonomous AI agent system** that, once initialized via one API call, independently discovers AI/security topics, judges them editorially through a multi-stage pipeline, writes posts in a consistent persona voice, critiques and revises its own drafts, remembers what it has published (including how topics evolve over time), and keeps publishing on its own — with zero further human or API input. Follow this document precisely. Where a decision is left open, make the pragmatic choice that favors **reliability, autonomy, and demonstrability under judging**, and tell me what you chose and why.

Do not skip the "Editorial Judgment," "Self-Critique," and "Rationale" requirements — they are explicitly graded and easy to under-build.

---

## 1. Competition Requirements (verbatim contract — do not deviate)

Two HTTP endpoints only:

```
POST /api/agent/init
Request:  { "persona": { "name": "Ada", "domain": "AI Security" } }
Response: { "agentId": "abc-123" }
```

```
GET /api/agent/feed?agentId=abc-123
Response: {
  "posts": [
    {
      "id": "p7",
      "createdAt": "2026-08-07T10:30:00Z",   // ISO 8601 UTC
      "text": "...",
      "rationale": "Why this topic was selected, why it is relevant now, and why it was chosen over other candidates.",
      "sources": ["https://..."]
    }
  ]
}
```

Hard rules:
- `init` is called **exactly once**. After that, **no more prompts**. The agent must run itself.
- New posts must appear in the feed over the ~48-hour observation window **without any further calls other than polling `GET /feed`**.
- Posts: unique `id`, reverse-chronological order, previously returned posts stay available (never delete/renumber).
- Empty state returns `{ "posts": [] }`.
- Must show genuine **editorial judgment** — i.e., provably reject topics, not just publish everything discovered.
- Must have **memory** — avoid repeating previously covered topics/angles, but recognize when a topic has genuinely evolved and deserves a follow-up.
- Persona must be consistent (voice, interests, opinions) and stay in the AI/tech domain.
- Simulated publishing is fine — no real social platform integration required.

Non-requirements (don't waste hackathon time on these): real social media posting, multi-platform, images/video, engagement analytics, multi-agent orchestration, human intervention after init.

---

## 2. The Persona (concrete default — implement this one)

**Name:** Wren
**Domain:** AI Security — specifically the intersection of adversarial ML, LLM security (jailbreaks, prompt injection, data exfiltration), AI supply-chain risk (model/package provenance), and network-facing AI systems security.

> This persona was selected because AI Security provides a focused domain with abundant, credible, live information sources (arXiv, CVE databases, security research blogs, GitHub activity) and clear, checkable opportunities for editorial judgment — a topic either has a verifiable technical mechanism and credible source, or it doesn't. That makes the domain well suited to demonstrating genuine editorial reasoning rather than generic trend-following.

### 2.1 Voice & Editorial Bible (bake this into the LLM system prompt verbatim, adapt lightly)

```
You are Wren, an independent AI Security Researcher. You write short, sharp,
technically literate takes on developments in AI security — adversarial ML,
LLM jailbreaks/prompt injection, model supply-chain risk, and security of
AI-adjacent network infrastructure.

Voice:
- Precise and slightly dry. No hype, no emoji, no exclamation points.
- You explain the *mechanism*, not just the headline — one concrete technical
  detail per post minimum.
- You have opinions and state them plainly ("this is overstated", "this is
  the real risk, not the one everyone's discussing").
- 2–5 short sentences per post. Thread-of-thought, not a press release.
- You never use marketing language ("game-changing", "revolutionary",
  "exciting"). You are allowed to be skeptical or even mildly dismissive of
  hype-y topics.
- You close with either an implication ("this changes X for defenders") or a
  pointed question — never a generic call-to-action.

Stable interests (weight discovery/scoring toward these):
1. Prompt injection & jailbreak techniques/defenses
2. Adversarial examples & evasion in ML classifiers
3. AI supply chain (model weights, datasets, MCP servers, package registries)
4. Security of AI agents with tool/network access
5. Notable CVEs or incidents touching ML systems
6. Research papers with a concrete exploit or defense, not just benchmarks

Topics Wren explicitly is NOT interested in (auto-reject fodder):
- Pure product launches / funding announcements with no security angle
- General "AI is changing X industry" trend pieces
- Model benchmark leaderboard news with no security relevance
- Anything requiring speculation without a credible source
```

Keep this text in a constants file (e.g., `PersonaProfile.java`) — every LLM call for judgment, writing, or critique references it, so voice stays consistent across the whole 48 hours.

---

## 3. System Architecture

```
┌───────────────────────────────────────────────────────────────────────┐
│  Spring Boot App (single deployable service, always-on)                │
│                                                                         │
│  ┌───────────────┐   ┌────────────────────┐   ┌──────────────────┐    │
│  │ REST Layer    │   │ Scheduler           │   │ Persistence       │    │
│  │ /agent/init   │   │ @Scheduled loop     │   │ PostgreSQL        │    │
│  │ /agent/feed   │   │ (runs per agent)    │   │ (Supabase)        │    │
│  │ /health       │   │ + failure/queue mgr │   └────────┬──────────┘    │
│  │ /agent/metrics│   └─────────┬──────────┘            │               │
│  └───────────────┘             │                       │               │
│                                 ▼                       │               │
│                    ┌─────────────────────────┐          │               │
│                    │ Multi-Stage Pipeline     │◄─────────┘               │
│                    │  (see Section 7)         │                          │
│                    └─────────────┬────────────┘                          │
│                                  │                                        │
│         ┌────────────────────────┼─────────────────────┐                 │
│         ▼                        ▼                      ▼                │
│  Topic Discovery Adapters   LLM Provider Router    RAG Memory Layer      │
│  (arXiv, HN, GitHub, NVD)   (Gemini→Groq→           (recent posts,       │
│                              OpenRouter→Cerebras)    opinions, topic      │
│                                                       embeddings)         │
└───────────────────────────────────────────────────────────────────────┘
                │
                ▼
      External keep-alive cron (cron-job.org) pings /health every
      ~10 min so the free-tier host never sleeps during the 48h window.
```

---

## 4. Tech Stack

| Layer | Choice | Why |
|---|---|---|
| Backend | Java 21 + Spring Boot 3.x, Maven | Fast to scaffold, `@Scheduled` gives free autonomous background execution, no separate worker process needed |
| DB | PostgreSQL via Supabase (free tier) | Durable state across restarts; also usable for lightweight memory/embedding queries |
| LLM | **Provider-abstracted** — see 4.1 | Extensibility and resilience are explicitly judged |
| Topic sources | arXiv API, Hacker News (Algolia) API, GitHub REST (trending/search), NVD CVE API | All free, no API key required (or trivially obtained), directly aligned to persona domain |
| Scheduling | Spring `@Scheduled(fixedDelay=...)` + `ThreadPoolTaskScheduler` | Native autonomy, no external cron dependency for the core loop |
| Deployment | Render (backend) | Reuse known-working deployment pattern; add external keep-alive cron hitting `/health` |
| Optional viewer | Minimal static HTML/React feed viewer | Not required by spec but useful for live demo to judges |

### 4.1 LLM Provider Abstraction (build this, do not hardcode one vendor)

Define a `LlmProvider` interface, not a direct SDK call scattered through the code:

```java
public interface LlmProvider {
    String name();
    boolean isAvailable();                      // cheap health/config check
    LlmResponse complete(LlmRequest request);    // throws LlmProviderException on failure
}
```

Implement one adapter class per provider. **Priority order** (configurable, not hardcoded logic):

1. **Gemini** (primary)
2. **Groq** (fast fallback)
3. **OpenRouter** (broad model access fallback)
4. **Cerebras** (secondary fast fallback)

A `LlmProviderRouter` component reads the ordered priority list from an environment variable (e.g. `LLM_PROVIDER_PRIORITY=gemini,groq,openrouter,cerebras`), and for every call:
- Tries providers in priority order.
- Skips a provider immediately if its API key env var is missing (don't hard-fail the app if you only configure 1–2 providers during the hackathon).
- On a request failure (timeout, rate limit, 5xx), falls through to the next provider **within the same pipeline stage** rather than failing the whole tick.
- Logs which provider actually served each request (needed for the observability layer, Section 9).

This means the system keeps functioning even if your primary key runs out of free-tier quota mid-evaluation — realistically likely over 48 hours.

---

## 5. Data Model (PostgreSQL DDL)

```sql
CREATE TABLE agents (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  persona_name TEXT NOT NULL,
  persona_domain TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | PAUSED
  initialized_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_tick_at TIMESTAMPTZ
);

CREATE TABLE posts (
  id TEXT PRIMARY KEY,                      -- human-friendly, e.g. p1, p2...
  agent_id UUID NOT NULL REFERENCES agents(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  text TEXT NOT NULL,
  rationale TEXT NOT NULL,
  sources TEXT[] NOT NULL,
  topic_key TEXT NOT NULL,                  -- normalized topic fingerprint, for memory/dedup
  is_followup_of TEXT REFERENCES posts(id), -- non-null if this is an "opinion evolution" follow-up
  confidence NUMERIC,                       -- internal, not required by API but used for gating
  editorial_score NUMERIC,
  llm_provider_used TEXT
);

CREATE TABLE topic_candidates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  agent_id UUID NOT NULL REFERENCES agents(id),
  discovered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  source TEXT NOT NULL,                     -- arxiv | hn | github | nvd
  raw_title TEXT NOT NULL,
  raw_url TEXT NOT NULL,
  credibility_tier TEXT,                    -- see Section 7.4
  editorial_score NUMERIC,
  confidence NUMERIC,
  persona_alignment_score NUMERIC,
  decision TEXT NOT NULL,                   -- ACCEPTED | REJECTED | QUEUED
  decision_reason TEXT NOT NULL,
  resulted_post_id TEXT REFERENCES posts(id)
);

CREATE TABLE memory_entries (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  agent_id UUID NOT NULL REFERENCES agents(id),
  topic_key TEXT NOT NULL,
  summary TEXT NOT NULL,
  opinion_stance TEXT,                      -- Wren's stated position, for consistency + evolution checks
  embedding VECTOR(768),                    -- pgvector; nullable if not implemented yet
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pipeline_metrics (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  agent_id UUID NOT NULL REFERENCES agents(id),
  tick_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  candidates_discovered INT,
  candidates_rejected INT,
  candidates_accepted INT,
  avg_editorial_score NUMERIC,
  llm_provider_used TEXT,
  llm_latency_ms INT,
  api_failures INT,
  self_critique_revisions INT
);
```

`topic_candidates` is the receipts table for editorial judgment — even though the spec doesn't require exposing rejected topics via the API, **persist them anyway**. It's your strongest evidence for the "quality of editorial decision-making" judging criterion.

---

## 6. Autonomy & Failure Recovery Design — the part judges will actually test

1. **Init is idempotent-safe but truly single-shot per agent.** `POST /agent/init` creates one row in `agents`, generates `agentId`, and returns immediately (do NOT run the first pipeline tick synchronously inside the request).
2. **DB-driven resumption.** On app startup (including a redeploy/restart mid-evaluation), read all `ACTIVE` agents from Postgres and resume scheduling for each. Never rely on in-memory-only state for "has this agent been initialized."
3. **Tick interval:** ~45–90 minutes per agent, randomized slightly (`60 ± 15 min`) to look organic. Log the exact next-run time.
4. **Failure recovery chain (per tick, per stage):**
   ```
   Primary LLM provider fails
        ↓
   Router switches to next provider in priority order
        ↓
   Retry the same pipeline stage (not the whole tick) with new provider
        ↓
   If ALL providers fail for this tick → do not crash the scheduler.
   Persist the tick's partial state (discovered candidates, any completed
   stages) to topic_candidates with decision = 'QUEUED'
        ↓
   Next scheduled tick picks up QUEUED candidates first, before discovering
   new ones, so no work is silently lost and autonomy is never interrupted.
   ```
5. **Discovery-source failure** (e.g., arXiv API down) is handled independently per adapter — one failing source should never block the others; the tick proceeds with whatever candidates were successfully gathered.
6. **Keep-alive:** register a free external cron (cron-job.org) to `GET /health` every ~10 minutes for the full 48h window so the JVM (and therefore the in-process scheduler) never sleeps between evaluator polls.
7. **No pipeline step should ever require a human/API trigger.** A dev-only "force tick" endpoint for your own testing is fine, but gate it behind a header/token and keep it clearly outside the competition's two required endpoints.

---

## 7. Pipeline Detail — Multi-Stage Editorial Workflow

Replace a naive "discover → judge → publish" loop with the following explicit stages. Each stage should be its own method/component so it's independently testable and independently loggable for the metrics layer.

```
1. Discover
2. Normalize
3. Deduplicate
4. Credibility Check
5. Editorial Score
6. Persona Alignment
7. Publish Decision
8. Write (draft)
9. Self-Critique & Revise
10. Memory Update (RAG write-back)
```

### 7.1 Discover
Call 2–4 sources per tick, pull ~5–10 raw candidates total, from:
- **arXiv API** — recent papers in `cs.CR`, `cs.AI`, `cs.LG` matching persona keywords (jailbreak, adversarial, prompt injection, supply chain, backdoor, poisoning).
- **Hacker News via Algolia API** — filter by AI/security keywords, recency, and a minimum points threshold.
- **GitHub REST search** — trending repos touching LLM security, red-teaming tools, adversarial ML libraries.
- **NVD CVE API** — recent CVEs mentioning ML frameworks (PyTorch, TensorFlow, LangChain, vector DBs, MCP, etc.).

All free/keyless or trivially-keyed. Store every raw candidate.

### 7.2 Normalize
Strip HTML/markdown noise, unify each candidate into a common shape: `{title, summary, url, source, publishedAt}`. Extract a first-pass `topic_key` (lowercased, keyword-stemmed slug) used by the next stage.

### 7.3 Deduplicate
Two layers:
- **Intra-tick:** collapse near-identical candidates discovered from multiple sources this tick (e.g., same CVE mentioned on HN and in GitHub search).
- **Cross-time (memory-aware):** compare `topic_key` and a short embedding/keyword similarity check against `memory_entries`. If a strong match exists, don't auto-reject yet — pass it to stage 5 flagged as `possible_followup=true` so the Editorial Score stage can decide "duplicate" vs. "opinion evolution" (see Section 7.7).

### 7.4 Credibility Check
Assign a `credibility_tier` per candidate based on source, not content:

| Tier | Sources | Treatment |
|---|---|---|
| A — High | arXiv papers, NVD/CVE entries, NIST, official vendor security blogs | Eligible for publish |
| B — Medium | GitHub (maintainer-verified repos), Hacker News (points above threshold) | Eligible, but needs stronger editorial score |
| C — Low / Reject | Random blogs, unverified aggregators, content that reads as AI-generated news, clickbait-style titles | Auto-reject before spending an LLM call — cheap early filter |

Doing this filter with simple heuristics (source whitelist, domain checks, title pattern checks) *before* the LLM call saves latency/cost and gives you a clean, explainable rejection reason for low-tier junk.

### 7.5 Editorial Score (LLM call — "judge")
For remaining (Tier A/B) candidates, call the LLM Provider Router with the persona bible + rolling memory summary (RAG context, Section 8) and require **structured JSON only**:

```json
{
  "decisions": [
    {
      "candidate_index": 0,
      "topic": "short topic label",
      "score": 0-100,
      "confidence": 0-100,
      "publish": true,
      "is_followup_of_topic_key": null,
      "reason": "specific, non-generic reason"
    }
  ]
}
```

Rubric baked into the prompt:
- Fit with persona's stable interests (Section 2.1). Off-domain → `publish:false`.
- Technical substance — must explain a real mechanism, not just report a headline.
- If flagged `possible_followup` from stage 7.3: is this a genuine new development (fix, exploit, disclosure, escalation) vs. a rehash? If genuine → set `is_followup_of_topic_key`, don't treat as duplicate.
- If multiple strong candidates this tick overlap in subject, keep the highest `score` and reject the rest with reason referencing the winning topic — this satisfies "why chosen over other candidates."
- **Gate:** only candidates with `confidence >= 70` AND `publish: true` proceed. Anything below is rejected with reason `"confidence below publishing threshold ({X}/100)"`. This is the confidence-gating mechanism — makes editorial judgment believable and testable, not just a coin flip.

Persist every decision (accepted and rejected) to `topic_candidates`, including `credibility_tier`, `editorial_score`, `confidence`, and `decision_reason`.

### 7.6 Persona Alignment (second, cheaper pass)
A lightweight check (can be rule-based, not necessarily another LLM call) confirming the winning candidate doesn't push Wren into a domain drift — cross-reference against the "explicitly NOT interested in" list in Section 2.1 as a final guardrail before writing.

### 7.7 Publish Decision
Pick at most **one** candidate to actually publish this tick (keeps pacing realistic — see Section 6.3). If the winning candidate is a follow-up (`is_followup_of_topic_key` set), mark it as such — this becomes `posts.is_followup_of` and should explicitly be framed as "new angle on a topic we covered before," not a repeat. Example: Day 1 post covers a new model release; Day 2 a security flaw is found in it — publish a follow-up referencing the earlier post rather than either repeating it or ignoring it.

### 7.8 Write (draft)
Call the LLM Provider Router with: persona bible, the winning candidate, RAG context (Section 8), and — if a follow-up — the original post's text and stance for continuity. Require structured JSON:

```json
{
  "topic": "",
  "post": "",
  "rationale": "",
  "sources": ["https://..."],
  "confidence": 0-100
}
```

Length cap: under ~500 characters (long-tweet/LinkedIn-micro-post length).

### 7.9 Self-Critique & Revise ⭐ (do not skip — this is the highest-value addition)
Before persisting, run a second LLM call — same or different provider — with the draft and a critique prompt:

```
Review this draft post from Wren, an AI Security Researcher persona.

Check:
1. Is it factually supported by the provided source material (no fabricated
   specifics)?
2. Is it consistent with Wren's established voice and prior stated opinions
   (see recent posts/opinions below)?
3. Is it non-repetitive relative to recent posts?
4. Is it substantive enough to be worth publishing, or generic filler?

Return JSON:
{
  "verdict": "PUBLISH" | "REVISE" | "REJECT",
  "issues": ["..."],
  "revised_post": "..."   // only if verdict is REVISE
}
```

- `PUBLISH` → proceed to persist as-is.
- `REVISE` → use `revised_post`, log a revision count (feeds observability metrics).
- `REJECT` → abandon this candidate for the tick, log to `topic_candidates` with reason from `issues`, and — if time remains in the tick's budget — fall back to the next-highest-scoring candidate from stage 7.5 instead of publishing nothing.

This two-pass draft→critique→revise loop is what elevates the system from "an LLM posting on a schedule" to a genuine agentic workflow, and should be visible in your architecture diagram and demo narration.

### 7.10 Memory Update (RAG write-back)
Insert into `memory_entries`: `topic_key`, a 1-sentence summary, `opinion_stance` (Wren's take, for future consistency checks), and — if implemented — an embedding vector for future similarity search. Insert the `posts` row (including `confidence`, `editorial_score`, `llm_provider_used`, `is_followup_of`). Update `agents.last_tick_at`. Write a `pipeline_metrics` row summarizing the tick (Section 9).

---

## 8. Memory as a RAG Layer

Treat memory not as a simple "have I seen this before" boolean, but as a lightweight retrieval layer feeding every generation call:

**Before every judge/write/critique call, retrieve:**
- Last ~10 published posts (for voice and phrasing continuity)
- Recent stated opinions/stances per topic (`memory_entries.opinion_stance`) — so Wren doesn't contradict itself
- Topic embeddings (or keyword fingerprints if pgvector isn't wired up in time) for similarity/duplicate/follow-up detection
- Any standing editorial preferences you've hardcoded (Section 2.1's interest list acts as a static "preferences" retrieval source)

Start with keyword/substring `topic_key` matching (fast to build, good enough for a 48-hour window with a modest post volume). Upgrade to `pgvector` embedding similarity only if time allows — this is explicitly a stretch goal (Section 12), not a blocker for a working submission. Whichever you use, frame it explicitly in your README/demo as "memory functions as a lightweight RAG layer: relevant context is retrieved and injected before every generation call" — this is a specific, checkable claim you should be able to point at in the code.

---

## 9. Observability & Metrics

Expose a simple internal metrics view (can be a non-graded debug endpoint, log output, or a small dashboard) tracking, per agent, across the run:

- Posts generated
- Topics discovered vs. rejected (with breakdown by rejection stage: credibility / editorial score / confidence gate / persona alignment / self-critique)
- Average editorial score and average confidence of published posts
- Average publish interval (actual, vs. configured target)
- Memory hits / duplicate-avoidance count
- Follow-up ("opinion evolution") count
- LLM provider usage breakdown (which provider served how many calls) and failover count
- LLM latency (avg, per provider)
- API/tool failures per tick
- Scheduler health (last successful tick time, missed-tick count)

Populate this from the `pipeline_metrics` table (Section 5). Even a simple `GET /api/agent/metrics?agentId=` debug endpoint (clearly marked non-competition-surface) is strong demo material — judges respond well to being able to see the system reasoning about itself, not just its output.

---

## 10. API Implementation Notes

- `POST /api/agent/init`: validate persona payload, insert `agents` row, register/resume the scheduled job for that agent id, return `{ "agentId": <uuid> }` immediately (don't block on first pipeline run).
- `GET /api/agent/feed?agentId=...`: query `posts` for that agent, `ORDER BY created_at DESC`, map to the exact response shape in Section 1 — field names, ISO timestamp format, array types must match exactly. Internal-only fields (`confidence`, `editorial_score`, `llm_provider_used`, `is_followup_of`) stay out of this response unless you want to enrich it — the spec's shape is a minimum, not a ceiling, but don't break the required fields' exact names/types.
- `GET /health`: trivial 200 OK, used only for keep-alive pinging.
- `GET /api/agent/metrics?agentId=` and `GET /api/agent/candidates?agentId=`: optional, non-graded, dev/demo-only endpoints — gate behind a simple header/token if you're worried about surface creep, and keep them clearly documented as outside the required two-endpoint contract.
- CORS: open enough for a demo viewer/judges to hit from a browser if you build one.
- No auth required on the two required endpoints (single-user, matches the spec's simplicity).

---

## 11. Build Order (follow this sequence with the AI coding agent)

1. **Scaffold** Spring Boot project (Web, JPA, PostgreSQL driver, Validation, Scheduling). Wire Supabase connection string via env vars — URL-encode special characters in the password, and set `prepareThreshold=0` in the JDBC URL to avoid pgBouncer prepared-statement conflicts.
2. **Entities + repositories** for `Agent`, `Post`, `TopicCandidate`, `MemoryEntry`, `PipelineMetrics` matching Section 5.
3. **`POST /api/agent/init`** + **`GET /api/agent/feed`** endpoints against manually-inserted test rows — verify exact JSON shape matches Section 1 byte-for-byte before building anything else.
4. **LLM Provider Router** (Section 4.1) — interface, one adapter first (whichever provider you can key fastest), verify a real structured-JSON round trip, *then* add the remaining adapters and the priority-fallback logic.
5. **Discovery adapters** — implement one at a time (arXiv first, simplest XML/Atom), test each in isolation before wiring into the pipeline.
6. **Stages 7.2–7.4** (Normalize, Deduplicate, Credibility Check) — pure logic, no LLM calls, unit-testable in isolation.
7. **Stage 7.5 Editorial Score** — test against hand-picked real candidates (mix of clearly-on-topic and clearly-off-topic, plus a real duplicate and a real "evolution" pair) and confirm the rubric behaves.
8. **Stage 7.8 Write** — sanity-check voice consistency across 5–10 generated samples.
9. **Stage 7.9 Self-Critique** — deliberately feed it a bad/generic draft and confirm it revises or rejects; feed it a good draft and confirm it passes through.
10. **Stage 7.10 Memory/RAG write-back** — confirm retrieval context actually changes model output (e.g., ask it about a topic it "already covered" and confirm it produces a follow-up framing, not a duplicate).
11. **Scheduler + failure recovery** (Section 6) wiring — test locally with a short interval override via a profile/env flag; simulate a provider failure (bad API key) and confirm fallback + `QUEUED` recovery works instead of killing the loop.
12. **Restart-resilience test**: kill and restart the app locally mid-run, confirm the agent resumes and the feed still returns prior posts plus continues producing new ones.
13. **Metrics table + optional debug endpoints** (Section 9).
14. **Deploy** to Render, connect Supabase, set the real tick interval (45–90 min), set up the external keep-alive cron.
15. **Unattended soak test**: call `init` once, walk away for a few hours, come back and confirm the feed grew on its own with sane, non-repetitive, well-reasoned content before submitting.
16. **(Optional, time-permitting)** minimal static feed viewer page for live demo appeal.

---

## 12. AI Usage Log — do this as you go, not at the end

Stage 1 eligibility requires an **AI Usage Log**, and Stage 2 authenticity review checks that it reasonably corresponds to implemented features and isn't generic/incomplete.
- Keep a running `AI_USAGE_LOG.md` alongside this file, logged **as you send prompts**, roughly timestamped, tagged with which feature they produced.
- Commit early and often with real incremental history matching Section 11's steps — a single giant final commit is an explicit red flag in judging.
- Don't pre-create the repo before kickoff, and don't paste in an already-built codebase — build it live against this prompt.

---

## 13. Self-Check Against the Judging Rubric Before Submitting

- [ ] Called `init` exactly once on the **final** deployed instance.
- [ ] Left it running unattended for several hours and it produced new, distinct posts with no further calls beyond `GET /feed`.
- [ ] At least one clearly-documented rejection exists in `topic_candidates`, and ideally one from each rejection stage (credibility / score / confidence-gate / persona alignment / self-critique) — proves the pipeline isn't a rubber stamp.
- [ ] At least one follow-up/"opinion evolution" post exists, explicitly referencing an earlier post via `is_followup_of`.
- [ ] Read 5+ posts back to back — same author, same domain focus throughout?
- [ ] Every post's `rationale` answers all three required questions (why selected / why now / sources).
- [ ] `sources` array contains real, working URLs.
- [ ] Feed JSON shape matches spec exactly, empty state returns `{"posts": []}`.
- [ ] Live demo URL works cold and survives idle time (keep-alive verified).
- [ ] Confirm the provider router actually fails over (test by temporarily disabling the primary key).
- [ ] `AI_USAGE_LOG.md` present, dated, and matches the shipped feature set.

---

## 14. Stretch Goals (only after Sections 1–13 are solid)

- `pgvector` embedding-based memory instead of keyword `topic_key` matching, for smarter dedup/follow-up detection on paraphrased topics.
- A small live dashboard rendering the `pipeline_metrics` data as charts for the demo.
- Sentiment/opinion variety tracking so Wren rotates rhetorical stances rather than always landing the same beat.
- A minimal feed-viewer front-end styled like a single-author blog/microblog.

---

*End of build spec. When steering the coding agent mid-hackathon, quote the specific section number you're addressing (e.g., "per Section 7.9, tighten the self-critique rubric so...") to keep instructions unambiguous and keep this file the single source of truth.*
