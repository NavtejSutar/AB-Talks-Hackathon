# Implementation Plan — "Wren": Autonomous AI Security Researcher Persona

Build an autonomous AI agent system in Java 21 / Spring Boot 3.x that discovers AI security topics, evaluates them editorially, drafts/critiques posts, remembers history, and publishes autonomously over a 48-hour window with zero user interaction after `POST /api/agent/init`.

## User Confirmed Choices

- **Init Retry Behavior**: Repeat `POST /api/agent/init` calls create a new independent `Agent` instance.
- **Persona Handling**: Request's `persona.name` is used as a display substitution in the fixed Wren voice/interest bible.
- **Self-Critique Fallback Cap**: Maximum 2 fallback candidates per tick if self-critique rejects a draft before producing no post for that tick.
- **Tie-Break Rule**: Prioritize higher `credibility_tier` (Tier A > Tier B), then most recent `publishedAt`.
- **Deployment Tier**: Free Render web service paired with external keep-alive cron hitting `GET /health` every 10 minutes, plus an in-app post-tick self-ping fallback as a second layer of defense against dyno sleep.

---

## Proposed Implementation Tasks

The implementation will follow the 30-task sequence detailed in [`TASKS.md`](file:///c:/Users/divsutar/OneDrive/Desktop/navproject/AB%20Talks%20Hackathon/TASKS.md).

### Phase 1: Project Scaffolding & Core API Foundation (Tasks 1–9)
- **Task 1 — Project Scaffolding**: Create Maven project with Java 21, Spring Boot 3.x, JPA, Web, Validation, PostgreSQL driver, and package structure.
- **Task 2 — Environment & Infrastructure**: Provision `.env.example` defining database URLs, LLM API keys (Gemini, Groq, OpenRouter, Cerebras), GitHub token, and NVD key.
- **Task 3 — Flyway Database Migration**: Create `V1__init_schema.sql` implementing `agents`, `posts`, `topic_candidates`, `memory_entries`, and `pipeline_metrics` tables with atomic sequence support.
- **Task 4 — Datasource & Pool Configuration**: Configure `application.yml` and `DataSourceConfig.java` with Supabase pgBouncer settings (`prepareThreshold=0`) and HikariCP connection pool limit (size 5).
- **Task 5 — JPA Entities & Repositories**: Implement entity classes with custom `sources TEXT[]` mapping and corresponding Spring Data JPA repositories.
- **Task 6 — DTOs & Jackson Timestamp Configuration**: Create DTOs enforcing byte-for-byte contract matching and ISO-8601 UTC timestamp formatting.
- **Task 7 — Implement `POST /api/agent/init`**: Accept persona payload, insert `agents` row, initialize post sequence to 0, and return `agentId`.
- **Task 8 — Implement `GET /api/agent/feed`**: Query agent posts in reverse-chronological order, returning exact spec JSON shape, `{ "posts": [] }` for empty feeds, and `404` for unknown agents.
- **Task 9 — Exception Handler & API Contract Tests**: Implement `GlobalExceptionHandler` and `@WebMvcTest` suite verifying exact JSON contract byte-for-byte.

### Phase 2: LLM Provider Router & Resilient Fallback (Tasks 10–13)
- **Task 10 — Provider Interface & JSON Repair Parser**: Define `LlmProvider` interface and markdown code-fence stripper with repair retry logic.
- **Task 11 — Gemini Provider Adapter**: Implement primary provider adapter for Gemini REST API.
- **Task 12 — Fallback Provider Adapters**: Implement Groq, OpenRouter, and Cerebras adapters with `isAvailable()` health checks.
- **Task 13 — Provider Router with Failover**: Implement `LlmProviderRouter` trying configured priority order (`Gemini → Groq → OpenRouter → Cerebras`) and logging failovers.

### Phase 3: Discovery Adapters & Filtering (Tasks 14–19)
- **Task 14 — arXiv Discovery Adapter**: Fetch and parse Atom XML feed for `cs.CR`, `cs.AI`, `cs.LG`.
- **Task 15 — Hacker News & GitHub Adapters**: Algolia HN API search + authenticated GitHub repository search.
- **Task 16 — NVD Adapter & Discovery Stage**: NVD CVE search + aggregated discovery with fault isolation across adapters.
- **Task 17 — Normalization & Topic Key Generation**: Strip HTML/markdown noise and compute normalized `topic_key` slug.
- **Task 18 — Memory Retrieval Service**: RAG query helper for recent posts and topic memory.
- **Task 19 — Deduplication & Credibility Stages**: Intra-tick collapse, cross-time `possible_followup` flagging, and Tier A/B/C source filtering.

### Phase 4: Editorial Pipeline & Persona Reasoning (Tasks 20–26)
- **Task 20 — Persona Profile Constants**: Voice bible, stable interests, and exclusion rules in `PersonaProfile.java`.
- **Task 21 — Editorial Score Stage**: Structured JSON LLM scoring, confidence gating ($\ge 70$), and receipt persistence in `topic_candidates`.
- **Task 22 — Persona Alignment & Publish Selection**: Exclusion check + single winner selection applying tie-break rules.
- **Task 23 — Writing Stage**: Persona voice post drafting with ~500-char cap and structured rationale.
- **Task 24 — Self-Critique & Revision Loop**: Draft evaluation (`PUBLISH | REVISE | REJECT`) with max 2 fallback retries.
- **Task 25 — Memory Write-Back & Atomic ID**: Sequential ID allocation (`'p' || post_sequence`) and atomic DB persistence.
- **Task 26 — Pipeline Orchestration & Metrics**: 10-stage execution loop, `tick_id` traceability, and `pipeline_metrics` logging.

### Phase 5: Autonomous Scheduler, Recovery & Observability (Tasks 27–30)
- **Task 27 — Scheduler Registrar & Lock Manager**: Boot-time agent resumption, 45–90 min randomized scheduling, and overlap prevention.
- **Task 28 — Local Autonomy Soak Test**: Short interval (1–2 min) unattended execution verification.
- **Task 29 — Failure Recovery Verification**: Provider outage simulation (`QUEUED` state resumption) and process restart resilience.
- **Task 30 — Token-Gated Debug Endpoints**: `GET /api/agent/metrics` and `GET /api/agent/candidates` gated via `X-Debug-Token`.

---

## Verification Plan

### Automated Tests
- **API Contract Tests**: `mvn test -Dtest=AgentInitControllerTest,AgentFeedControllerTest`
- **LLM Router Failover Tests**: `mvn test -Dtest=LlmProviderRouterTest`
- **Pipeline Stage Unit Tests**: `mvn test -Dtest=*StageTest`
- **Integration Test**: `mvn test -Dtest=DatasourceMappingIntegrationTest`

### Manual Verification
- Execute `POST /api/agent/init` and verify initial database record creation.
- Execute `GET /api/agent/feed?agentId=...` and verify JSON format byte-for-byte.
- Run local autonomy soak test with a 1-minute tick override and verify autonomous feed generation over 15 minutes.
