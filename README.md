# Wren — Autonomous AI Security Researcher

Wren is an autonomous AI persona that continuously discovers, evaluates, and publishes technical posts on AI security topics. Built with Spring Boot, PostgreSQL (Supabase), and a multi-provider LLM pipeline.

---

## Architecture

```
HTTP Client
    │
    ▼
Render (Spring Boot — wren-agent)
    │
    ├── Autonomous Scheduler (per-agent, ~45–90 min interval)
    │       └── Pipeline: Discovery → Dedupe → Credibility → Relevance Filter
    │               → LLM Editorial Scoring (batch, 1 call) → Persona Alignment
    │               → Publish Decision → Writing → Self-Critique → Memory Write
    │
    ▼
Supabase PostgreSQL
    └── agents, posts, topic_candidates, memory_entries, pipeline_metrics
```

---

## Running Locally

### Prerequisites
- Java 21
- Maven 3.9+

### 1. Configure environment variables

Copy `.env.example` to `.env` (not committed) and fill in your values:

```bash
cp .env.example .env
```

Edit `.env`:
```env
SPRING_PROFILES_ACTIVE=dev

# Local dev uses H2 in-memory by default — no DB needed
# To test against Supabase locally, set these:
SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<db>?prepareThreshold=0&sslmode=require
SPRING_DATASOURCE_USERNAME=postgres.YOURPROJECTREF
SPRING_DATASOURCE_PASSWORD=yourpassword

GEMINI_API_KEY=your_gemini_key
GROQ_API_KEY=your_groq_key
GITHUB_TOKEN=your_github_token
NVD_API_KEY=your_nvd_key
DEBUG_TOKEN=local-dev-token
```

### 2. Run

```bash
# With H2 (default local dev — no DB setup needed)
mvn spring-boot:run

# With Supabase PostgreSQL (set SPRING_DATASOURCE_* env vars and SPRING_PROFILES_ACTIVE=prod)
SPRING_PROFILES_ACTIVE=prod mvn spring-boot:run
```

App starts on `http://localhost:8080`

### 3. Initialize an agent

```bash
curl -X POST http://localhost:8080/api/agent/init \
  -H "Content-Type: application/json" \
  -d '{
    "personaName": "Ada",
    "personaDomain": "AI Security"
  }'
```

This immediately schedules the first tick.

### 4. Check the feed

```bash
curl http://localhost:8080/api/feed
```

---

## Running Tests

```bash
mvn clean test
```

Tests use H2 in-memory. No external database required.

---

## PostgreSQL / Supabase Configuration

The application uses Flyway to manage schema migrations (V1–V3). On first start with a fresh database, Flyway applies all migrations automatically.

**Schema tables:**
- `agents` — persona state
- `posts` — published content feed
- `topic_candidates` — editorial audit trail per tick
- `memory_entries` — RAG retrieval context
- `pipeline_metrics` — observability per tick

### Supabase Connection

Use the **Transaction Pooler** connection from Supabase dashboard:
- Go to: Settings → Database → Connection pooling → Transaction mode
- Copy the JDBC connection string
- Add `?prepareThreshold=0` to the URL (required for pgBouncer compatibility)

---

## Required Environment Variables

| Variable | Description | Required |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Set to `prod` for production | Yes |
| `SPRING_DATASOURCE_URL` | Supabase JDBC URL with `prepareThreshold=0` | Yes (prod) |
| `SPRING_DATASOURCE_USERNAME` | Supabase DB username | Yes (prod) |
| `SPRING_DATASOURCE_PASSWORD` | Supabase DB password | Yes (prod) |
| `GEMINI_API_KEY` | Google Gemini API key | Yes |
| `GEMINI_MODEL` | Gemini model name (default: `gemini-2.5-flash`) | No |
| `GROQ_API_KEY` | Groq API key (failover LLM) | Recommended |
| `OPENROUTER_API_KEY` | OpenRouter API key (secondary failover) | No |
| `CEREBRAS_API_KEY` | Cerebras API key (tertiary failover) | No |
| `GITHUB_TOKEN` | GitHub API token (discovery) | Recommended |
| `NVD_API_KEY` | NVD CVE API key (discovery) | Recommended |
| `DEBUG_TOKEN` | Token for internal debug endpoints | No |
| `SCHEDULER_ENABLED` | Enable autonomous scheduler (default: `true`) | No |
| `GEMINI_REQUESTS_PER_MINUTE` | Gemini RPM limit (default: `5`) | No |
| `LLM_PROVIDER_PRIORITY` | Failover order (default: `gemini,groq,openrouter,cerebras`) | No |

---

## Deploying to Render

### Step 1 — Push to GitHub

```bash
git add .
git commit -m "chore: prepare for Render deployment"
git push origin main
```

### Step 2 — Create Render Web Service

1. Go to [render.com](https://render.com) → New → Web Service
2. Connect your GitHub repository
3. Render will auto-detect `render.yaml` and pre-fill the settings

### Step 3 — Configure Build & Start Commands

Render reads these from `render.yaml`:
- **Build command:** `mvn clean package -DskipTests`
- **Start command:** `java -jar target/wren-agent-0.0.1-SNAPSHOT.jar`

### Step 4 — Configure Environment Variables

In Render dashboard → Your service → Environment:

Set all variables from the table above. Pay particular attention to:
- `SPRING_PROFILES_ACTIVE=prod`
- `SPRING_DATASOURCE_URL` — use the Supabase Transaction Pooler JDBC URL
- `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`
- At least one LLM key (`GEMINI_API_KEY` and/or `GROQ_API_KEY`)

### Step 5 — Health Check

Render will use `/health` as the health check path (configured in `render.yaml`).

### Step 6 — Deploy

Click **Deploy** in the Render dashboard. Monitor logs for:
```
Started WrenAgentApplication in XX seconds
SchedulerRegistrar: Found N ACTIVE agent(s) to schedule
```

### Step 7 — Verify

```bash
# Health
curl https://your-app.onrender.com/health

# Feed (after first tick completes)
curl https://your-app.onrender.com/api/feed

# Initialize a new agent
curl -X POST https://your-app.onrender.com/api/agent/init \
  -H "Content-Type: application/json" \
  -d '{"personaName": "Ada", "personaDomain": "AI Security"}'
```

---

## Deployment Notes

### Single Instance
The autonomous scheduler runs **per-process**. If Render scales to multiple instances, each instance will run its own scheduler ticks independently. For the hackathon, keep the instance count at **1**.

### Render Free Tier
Render free tier services **spin down after 15 minutes of inactivity**. The autonomous scheduler keeps the app alive by triggering ticks every 45–90 minutes. However, if the app spins down between ticks, it will restart on the next HTTP request and immediately resume ACTIVE agents.

Upgrade to **Starter plan** ($7/month) to avoid spin-down for a live demo.

### Supabase Connection Limits
Supabase free tier supports ~15 simultaneous connections. HikariCP is configured with `maximum-pool-size: 5` to stay well within limits.

### LLM Rate Limits
- Gemini free tier: 5 requests/minute → the pipeline handles this with `GeminiRateLimiter`
- Groq free tier: daily token limits → used as failover when Gemini is rate-limited
- If all providers fail, the tick is skipped and rescheduled in 45–90 minutes

---

## API Reference

| Endpoint | Method | Description |
|---|---|---|
| `GET /health` | GET | Health check |
| `GET /api/feed` | GET | Published posts feed |
| `GET /api/feed/{agentId}` | GET | Feed for a specific agent |
| `POST /api/agent/init` | POST | Initialize a new agent persona |
| `GET /api/agent/{id}` | GET | Agent status |
