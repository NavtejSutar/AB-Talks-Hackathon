# TASKS.md — Implementation Task Breakdown

> 30 tasks, each ~15–45 minutes, ordered to match dependency order (not the original roadmap's phase grouping — this is the literal build sequence). No code is written here — each task is a spec for what you'll ask your AI coding agent to build next. File paths match `ARCHITECTURE.md` Part C.
>
> **Assumption used below:** where a task touches one of the 5 open decisions from `ARCHITECTURE.md`, I've applied the *recommended* option (repeat-init allowed → new agent; persona name used as display substitution; tie-break = higher credibility tier then most recent; self-critique fallback cap = 2). Flag any of these in the task itself if you want a different call — swap it before that task starts, not after.
>
> Deployment and soak-testing are intentionally **not** in this list — they're operational/config steps, already sequenced as Phase 10–11 in `ARCHITECTURE.md` Part H, and don't fit the "implementation task" shape (no file/testing-step pair in the usual sense). Do them after Task 30.

---

### Task 1 — Scaffold the Spring Boot project
- **Goal:** Create a buildable, empty Spring Boot 3.x / Java 21 project with the full package skeleton so every later task has a known home.
- **Files:** `pom.xml`, `src/main/java/com/wren/agent/WrenAgentApplication.java`, empty package directories for `api`, `domain`, `scheduling`, `pipeline`, `discovery`, `llm`, `memory`, `persona`, `metrics`, `config` (per `ARCHITECTURE.md` Part C)
- **Dependencies:** None
- **Expected output:** `mvn spring-boot:run` starts a default Spring Boot app with no errors, empty package structure committed.
- **Testing steps:** Run `mvn clean install`; confirm build succeeds with zero source files beyond the main class; confirm the app starts and shuts down cleanly.

### Task 2 — Provision infrastructure & credentials
- **Goal:** Get every external dependency this project needs *provisioned* before any code depends on it, since NVD/GitHub credential approval has lag time.
- **Files:** None (external accounts/dashboards); create a local `.env.example` (not `.env`) listing every required variable name with no values.
- **Dependencies:** None
- **Expected output:** Supabase project created with `pgvector` extension enabled; NVD API key requested; GitHub personal access token generated; at least one LLM provider API key obtained (Gemini or Groq, whichever is fastest to get).
- **Testing steps:** Manually `curl` the Supabase Postgres connection string with `psql`; confirm the GitHub token works against `GET https://api.github.com/rate_limit` (should show 5000/hr, not 60/hr); confirm the LLM key works with a trivial manual API call.

### Task 3 — Write the Flyway schema migration
- **Goal:** Encode the full database schema from `ARCHITECTURE.md` Part D as a versioned migration.
- **Files:** `src/main/resources/db/migration/V1__init_schema.sql`
- **Dependencies:** Task 1, Task 2 (needs a live DB to run against)
- **Expected output:** One SQL file creating `agents`, `posts`, `topic_candidates`, `memory_entries`, `pipeline_metrics`, all indexes, and `agents.post_sequence` for atomic ID generation.
- **Testing steps:** Add Flyway dependency, run the app once, confirm all 5 tables + indexes exist in Supabase via `\dt` and `\d posts` in `psql`.

### Task 4 — Configure datasource, pool, and profiles
- **Goal:** Wire the app to Supabase correctly, including the pgBouncer/HikariCP gotchas already identified.
- **Files:** `src/main/resources/application.yml`, `src/main/resources/application-prod.yml`, `config/DataSourceConfig.java`
- **Dependencies:** Task 3
- **Expected output:** `application.yml` reads DB credentials from environment variables, sets `prepareThreshold=0` in the JDBC URL, and sets an explicit small HikariCP `maximum-pool-size` (e.g. 5). A `local`/`prod` profile split exists.
- **Testing steps:** Start the app locally against the real Supabase instance; confirm no prepared-statement or connection-exhaustion errors in logs after 20+ rapid manual requests to any placeholder endpoint.

### Task 5 — Implement JPA entities and repositories
- **Goal:** Map all 5 tables to entities and expose the specific queries the pipeline will need later.
- **Files:** `domain/entity/Agent.java`, `Post.java`, `TopicCandidate.java`, `MemoryEntry.java`, `PipelineMetricsRecord.java`; `domain/repository/AgentRepository.java`, `PostRepository.java`, `TopicCandidateRepository.java`, `MemoryEntryRepository.java`, `PipelineMetricsRepository.java`
- **Dependencies:** Task 3, Task 4
- **Expected output:** Entities compile and map correctly, including an explicit array-type mapping for `posts.sources TEXT[]` (this is the known Hibernate gotcha from `ARCHITECTURE.md` A.2 #5). Repositories include: `findByStatus('ACTIVE')` on `AgentRepository`, `findByAgentIdOrderByCreatedAtDesc` on `PostRepository`, `findByAgentIdAndTopicKey` on `MemoryEntryRepository`.
- **Testing steps:** Write a throwaway integration test that saves and reloads one row of each entity, specifically asserting the `sources` array round-trips correctly (this is the part most likely to silently fail).

### Task 6 — Implement request/response DTOs and timestamp config
- **Goal:** Lock in the exact required JSON shapes before any business logic exists, so nothing downstream can accidentally leak internal fields.
- **Files:** `api/dto/InitRequest.java`, `InitResponse.java`, `FeedResponse.java`, `PostResponseItem.java`; `config/JacksonConfig.java` (Instant → `2026-08-07T10:30:00Z`, no fractional seconds)
- **Dependencies:** Task 1
- **Expected output:** DTOs contain *only* the fields required by the competition contract (Section 1 of `PROMPT.md`). Jackson is configured to serialize `Instant` in exact ISO-8601-no-millis form.
- **Testing steps:** Write a serialization-only unit test: construct a `PostResponseItem` with a known `Instant`, serialize to JSON, assert the string matches `"2026-08-07T10:30:00Z"` exactly (not `...:00.000Z`).

### Task 7 — Implement `POST /api/agent/init`
- **Goal:** Accept the init payload, create an `Agent` row, return `agentId` fast — no pipeline logic yet, no scheduler yet.
- **Files:** `api/controller/AgentInitController.java`, a new `AgentService.java` (in `domain` or a new `agent` package)
- **Dependencies:** Task 5, Task 6
- **Expected output:** `POST /api/agent/init` with a valid payload returns `200` with a real `agentId`; `agents.post_sequence` initializes to 0; repeat calls create *additional independent* agents (per the applied assumption) rather than erroring; invalid/missing `persona` fields return `400`.
- **Testing steps:** `curl` the endpoint with a valid payload, confirm a new row in `agents`; call it twice, confirm two distinct `agentId`s exist; call with an empty body, confirm `400`.

### Task 8 — Implement `GET /api/agent/feed`
- **Goal:** Return posts for a given agent in the exact required shape, including empty-state and unknown-agent handling.
- **Files:** `api/controller/AgentFeedController.java`, `FeedService.java`
- **Dependencies:** Task 5, Task 6, Task 7
- **Expected output:** `GET /feed?agentId=<real>` with zero posts returns `{"posts": []}`; with an unknown `agentId` returns `404`; with a missing query param returns `400`.
- **Testing steps:** Manually insert 2–3 test rows into `posts` via `psql`, hit the endpoint, confirm reverse-chronological order and exact field names/types; confirm the 404 and 400 cases.

### Task 9 — Global exception handling + API contract tests
- **Goal:** Make error responses consistent, and lock the whole API contract down with automated tests before any pipeline work begins.
- **Files:** `api/error/GlobalExceptionHandler.java`, `src/test/java/com/wren/agent/api/AgentInitControllerTest.java`, `AgentFeedControllerTest.java`
- **Dependencies:** Task 7, Task 8
- **Expected output:** Consistent `{"error": "..."}` shape for all 4xx cases across both endpoints. Automated tests covering: successful init, successful feed with posts, empty feed, unknown agent, missing param.
- **Testing steps:** Run the new test suite; all pass; deliberately break one field name temporarily to confirm the test actually catches it (then revert).

### Task 10 — Define the LLM provider abstraction
- **Goal:** Build the interface layer everything else will call through — no real provider wired yet.
- **Files:** `llm/LlmProvider.java`, `llm/LlmRequest.java`, `llm/LlmResponse.java`, `llm/json/StructuredJsonParser.java`
- **Dependencies:** Task 1
- **Expected output:** `LlmProvider` interface (`name()`, `isAvailable()`, `complete()`); `StructuredJsonParser` that strips markdown code fences and does one "ask again for valid JSON only" repair retry before giving up.
- **Testing steps:** Unit test `StructuredJsonParser` against 3 fixture strings: clean JSON, JSON wrapped in ```` ```json ```` fences, and malformed JSON (confirm it throws a typed exception after the repair retry, not silently).

### Task 11 — Implement the Gemini provider adapter
- **Goal:** Get one real, live LLM call working end-to-end before building the other three.
- **Files:** `llm/providers/GeminiProvider.java`, `config/LlmProviderConfig.java` (partial)
- **Dependencies:** Task 2, Task 10
- **Expected output:** `GeminiProvider.complete()` makes a real API call and returns a parsed `LlmResponse` for a simple structured-JSON test prompt.
- **Testing steps:** Manual integration test hitting the real Gemini API with a "reply with `{\"ok\": true}`" prompt; confirm the round trip and parsing both succeed.

### Task 12 — Implement the remaining three provider adapters
- **Goal:** Complete the provider set so failover has something to fail over to.
- **Files:** `llm/providers/GroqProvider.java`, `OpenRouterProvider.java`, `CerebrasProvider.java`, `config/LlmProviderConfig.java` (complete — reads `LLM_PROVIDER_PRIORITY` env var)
- **Dependencies:** Task 11
- **Expected output:** All four adapters implement `LlmProvider` and pass the same "reply with JSON" smoke test as Task 11. Missing-API-key providers report `isAvailable() == false` rather than throwing.
- **Testing steps:** Run the same smoke test against each configured provider individually; unset one API key and confirm `isAvailable()` returns `false` instead of an exception.

### Task 13 — Implement the LLM provider router with failover
- **Goal:** The component every pipeline stage will actually call — priority order, failover, provider-used logging.
- **Files:** `llm/LlmProviderRouter.java`
- **Dependencies:** Task 12
- **Expected output:** `LlmProviderRouter.complete()` tries providers in configured priority order, skips unavailable ones, falls through on request failure, and logs which provider actually served the call.
- **Testing steps:** Unit test with 2 mocked `LlmProvider`s — first throws, second succeeds — confirm the router returns the second's result and logs the failover. Unit test with all mocked providers failing — confirm a clear typed exception, not a silent null.

### Task 14 — Discovery adapter interface + arXiv adapter
- **Goal:** First real external data source, and the interface the other three will follow.
- **Files:** `discovery/DiscoveryAdapter.java`, `discovery/ArxivDiscoveryAdapter.java`, `pipeline/model/RawCandidate.java`
- **Dependencies:** Task 1
- **Expected output:** `ArxivDiscoveryAdapter.fetch()` queries `cs.CR`/`cs.AI`/`cs.LG` for persona keywords, parses the Atom XML response, returns a list of `RawCandidate`.
- **Testing steps:** Call the adapter directly in a throwaway test/debug call, confirm 5–10 real, current arXiv results come back with title/summary/url/publishedAt populated.

### Task 15 — Hacker News + GitHub discovery adapters
- **Goal:** Add two more keyless-or-cheap-key sources.
- **Files:** `discovery/HackerNewsDiscoveryAdapter.java`, `discovery/GithubDiscoveryAdapter.java`
- **Dependencies:** Task 2 (GitHub token), Task 14 (interface + `RawCandidate`)
- **Expected output:** HN adapter queries Algolia `search_by_date` filtered by keyword + points threshold; GitHub adapter queries repository search filtered by relevant terms, using the token from Task 2 (5,000/hr limit, not 60/hr).
- **Testing steps:** Call each adapter directly, confirm real current results; confirm GitHub calls are authenticated (check response rate-limit headers show the higher ceiling).

### Task 16 — NVD adapter + Discovery stage aggregation
- **Goal:** Complete the source set and build the stage that calls all four with fault isolation.
- **Files:** `discovery/NvdDiscoveryAdapter.java`, `pipeline/stages/DiscoveryStage.java`
- **Dependencies:** Task 2 (NVD key), Task 15
- **Expected output:** `DiscoveryStage.discover()` calls all 4 adapters, catches and logs any individual adapter failure without failing the whole stage, and returns the combined `RawCandidate` list.
- **Testing steps:** Run the stage with all 4 adapters live — confirm a combined result set; then deliberately break one adapter's config (bad key) and confirm the stage still returns results from the other 3.

### Task 17 — Topic key generator + normalization stage
- **Goal:** Turn raw, heterogeneous candidates into a common shape with a first-pass topic fingerprint.
- **Files:** `memory/TopicKeyGenerator.java`, `pipeline/stages/NormalizationStage.java`, `pipeline/model/NormalizedCandidate.java`
- **Dependencies:** Task 16
- **Expected output:** `NormalizationStage.normalize()` strips HTML/markdown noise from each candidate and attaches a lowercased, keyword-stemmed `topic_key`.
- **Testing steps:** Unit test with a few hand-written fixture candidates (including one with HTML tags in the summary); confirm clean output text and a sane, stable `topic_key` for near-duplicate titles.

### Task 18 — Memory retrieval service
- **Goal:** Build the "R" in RAG before anything needs to consume it.
- **Files:** `memory/MemoryRetrievalService.java`
- **Dependencies:** Task 5
- **Expected output:** `getRecentPosts(agentId, n)` and `getRelevantMemory(topicKey, agentId)` methods backed by the repositories from Task 5.
- **Testing steps:** Seed 3–4 `memory_entries` and `posts` rows manually, call both methods, confirm correct ordering and filtering.

### Task 19 — Deduplication + credibility check stages
- **Goal:** The two pure-logic filtering stages that run before any LLM spend.
- **Files:** `pipeline/stages/DeduplicationStage.java`, `pipeline/stages/CredibilityCheckStage.java`
- **Dependencies:** Task 17, Task 18
- **Expected output:** `DeduplicationStage` collapses intra-tick near-duplicates and flags cross-time matches (via `MemoryRetrievalService`) as `possibleFollowup=true` rather than dropping them. `CredibilityCheckStage` assigns A/B/C tiers per the source rules in `ARCHITECTURE.md` Part A.4/Section 7.4 and rejects Tier C before any LLM call.
- **Testing steps:** Unit test dedup with 2 near-identical fixture candidates from different sources (confirm collapse) and one that matches an existing `memory_entries` row (confirm `possibleFollowup=true`, not dropped). Unit test credibility with one fixture per tier, confirm correct tier assignment and that Tier C never reaches the "eligible" output list.

### Task 20 — Persona profile constants
- **Goal:** Centralize Wren's voice bible so every later LLM-calling stage references the same source of truth.
- **Files:** `persona/PersonaProfile.java`
- **Dependencies:** Task 1
- **Expected output:** A constants class holding the voice bible text, stable-interests list, and exclusion list verbatim from `PROMPT.md` Section 2.1.
- **Testing steps:** No logic to test — confirm it compiles and is importable; sanity-read the constants against `PROMPT.md` Section 2.1 for an exact match.

### Task 21 — Editorial score stage
- **Goal:** The first real judgment call — the core of "editorial judgment" grading.
- **Files:** `pipeline/stages/EditorialScoreStage.java`, `pipeline/model/ScoredCandidate.java`
- **Dependencies:** Task 13, Task 19, Task 20
- **Expected output:** Calls `LlmProviderRouter` with the persona bible + RAG context + candidate batch, applies the confidence-gate (`confidence >= 70` AND `publish: true`), persists every decision (accepted and rejected) to `topic_candidates` with `decision_stage='EDITORIAL_SCORE'`.
- **Testing steps:** Run against a hand-picked fixture batch: one clearly on-topic, one clearly off-topic, one duplicate, one genuine "evolution" candidate. Confirm the off-topic one is rejected, the duplicate is either rejected or flagged as followup correctly, and the on-topic one passes the confidence gate.

### Task 22 — Persona alignment + publish decision stages
- **Goal:** Final guardrail and single-winner selection, including the tie-break rule.
- **Files:** `pipeline/stages/PersonaAlignmentStage.java`, `pipeline/stages/PublishDecisionStage.java`
- **Dependencies:** Task 21
- **Expected output:** `PersonaAlignmentStage` rejects anything matching the exclusion list even if it scored well. `PublishDecisionStage` picks exactly one winner; on a score tie, prefers higher `credibility_tier`, then most recent `publishedAt` (per the applied assumption).
- **Testing steps:** Unit test alignment with one fixture that scores well but matches an exclusion-list term — confirm rejection. Unit test publish decision with two fixture candidates at equal score but different tiers — confirm the higher-tier one wins.

### Task 23 — Writing stage
- **Goal:** Generate the actual post text, rationale, and sources in persona voice.
- **Files:** `pipeline/stages/WritingStage.java`, `pipeline/model/DraftPost.java`
- **Dependencies:** Task 18, Task 20, Task 22
- **Expected output:** Calls the router with persona bible, winning candidate, and RAG context (recent posts + stance if it's a followup); returns a `DraftPost` under the ~500-character cap with `rationale` containing all 3 required elements (why selected / why now / sources).
- **Testing steps:** Run against 5–10 different winning candidates, manually read each draft for voice consistency and rationale completeness; confirm none exceed the length cap.

### Task 24 — Self-critique stage
- **Goal:** The draft → critique → revise loop — flagged as the single highest-value addition to the pipeline.
- **Files:** `pipeline/stages/SelfCritiqueStage.java`
- **Dependencies:** Task 23
- **Expected output:** Calls the router with the draft + critique rubric, handles `PUBLISH`/`REVISE`/`REJECT`; on `REJECT`, falls back to the next-highest-scoring candidate from Task 22's ranked list, bounded at 2 fallback attempts before the tick cleanly produces no post.
- **Testing steps:** Feed it one deliberately bad/generic draft — confirm `REVISE` or `REJECT` fires, not a rubber-stamp `PUBLISH`. Feed it a solid draft — confirm `PUBLISH`. Force 3 consecutive rejections with fixture data — confirm the loop stops at 2 fallbacks rather than continuing indefinitely.

### Task 25 — Memory write-back
- **Goal:** Persist the published post and its memory footprint, including atomic ID generation.
- **Files:** `memory/MemoryWriteService.java`, `pipeline/stages/MemoryWriteStage.java`
- **Dependencies:** Task 24
- **Expected output:** Generates the next `posts.id` via an atomic `UPDATE agents SET post_sequence = post_sequence + 1 ... RETURNING` (not app-side counting); inserts the `posts` row and a matching `memory_entries` row (topic_key, summary, opinion_stance) in one transaction.
- **Testing steps:** Run two rapid sequential calls against the same agent (simulating a race), confirm no duplicate post IDs; confirm a DB failure mid-write rolls back both inserts (test by forcing a constraint violation).

### Task 26 — Pipeline orchestrator + metrics collector
- **Goal:** Wire all 10 stages into one sequenced tick, and capture what happened.
- **Files:** `pipeline/PipelineOrchestrator.java`, `metrics/PipelineMetricsCollector.java`
- **Dependencies:** Task 16, Task 17, Task 19, Task 21, Task 22, Task 23, Task 24, Task 25
- **Expected output:** `PipelineOrchestrator.runTick(agent)` executes all 10 stages in order, generates one `tick_id` shared across every `topic_candidates` row and the final `pipeline_metrics` row for that tick, and checks for and resumes any `QUEUED` candidates from a prior failed tick before discovering new ones.
- **Testing steps:** Run one full tick end-to-end against live discovery + live LLM calls; confirm a `pipeline_metrics` row appears with correct counts, and (if a candidate passed) a `posts` row appears with matching `tick_id` lineage in `topic_candidates`.

### Task 27 — Tick lock manager + scheduler registrar
- **Goal:** Make ticks autonomous, non-overlapping, and resumable across restarts.
- **Files:** `scheduling/TickLockManager.java`, `scheduling/SchedulerRegistrar.java`, `scheduling/AgentTickJob.java`, `config/SchedulingConfig.java`
- **Dependencies:** Task 26
- **Expected output:** `SchedulerRegistrar` reads `ACTIVE` agents from the DB on app boot and registers a per-agent job at a randomized 45–90 min interval; `TickLockManager` prevents two overlapping ticks for the same agent; `AgentInitController` (Task 7) now also registers a new job immediately after creating an agent.
- **Testing steps:** Set a short interval override via a profile flag (e.g. 1 minute), run locally, confirm 3+ consecutive autonomous ticks fire without any manual trigger and without overlapping (check `TickLockManager` logs).

### Task 28 — Local autonomy soak test (short interval)
- **Goal:** Prove the full loop works unattended before touching failure injection or deployment.
- **Files:** None (test-only task, may add a temporary test profile toggle in `application.yml`)
- **Dependencies:** Task 27
- **Expected output:** Running locally for ~15–20 minutes with a 1–2 minute tick interval produces multiple distinct, non-repetitive, on-voice posts with zero manual calls beyond the initial `init`.
- **Testing steps:** Call `init` once, poll `GET /feed` every few minutes, confirm growing post count, distinct topics, and correct reverse-chronological order throughout.

### Task 29 — Failure-injection and restart-resilience tests
- **Goal:** Prove the failure recovery chain and DB-driven resumption both actually work, not just exist on paper.
- **Files:** None (test-only task)
- **Dependencies:** Task 27
- **Expected output:** With all LLM provider keys temporarily invalidated, a tick produces `QUEUED` candidates instead of crashing the scheduler, and the next tick picks them up first. Killing and restarting the local app mid-run resumes scheduling for the existing agent and the feed still returns all prior posts.
- **Testing steps:** Comment out/break all 4 provider keys, trigger a tick, confirm `topic_candidates` rows with `decision='QUEUED'`, no exception in logs; restore one key, confirm next tick resolves the queue. Separately, kill the process mid-run and restart; confirm `GET /feed` still returns prior posts and a new tick eventually fires without calling `init` again.

### Task 30 — Debug controller (metrics + candidates)
- **Goal:** Non-competition, token-gated visibility into the pipeline's internal reasoning — your strongest demo/authenticity evidence.
- **Files:** `api/controller/DebugController.java`, `config/SecurityConfig.java` (token check + CORS)
- **Dependencies:** Task 26, Task 27
- **Expected output:** `GET /api/agent/metrics?agentId=` and `GET /api/agent/candidates?agentId=` return the shapes defined in `ARCHITECTURE.md` Part E.2, gated behind an `X-Debug-Token` header; requests without the correct token get `401`.
- **Testing steps:** Call both endpoints with and without the token, confirm `401` vs `200`; confirm the metrics numbers match what you observed manually during Tasks 28–29.

---

## After Task 30

Deployment (Render provisioning, Supabase pool tuning for prod, keep-alive cron setup) and the final unattended 48-hour soak test are operational steps, not implementation tasks — they're already sequenced as Phase 10–11 in `ARCHITECTURE.md` Part H. Do those once Tasks 1–30 are complete and locally verified.

---

*Still no code has been written. Tell me which task to start on — recommended: Task 1 through Task 9 in order first, since everything else depends on a working, contract-tested API + persistence layer.*
