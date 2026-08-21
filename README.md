# AI-Powered CI/CD Platform

A multi-agent DevOps platform that connects to your GitHub repositories through a
**GitHub App**, then automates the full CI/CD lifecycle with five AI agents
(brain: **Gemini 3.6 Flash via Google AI**) coordinated by an **AI Orchestrator**:

| Agent | What it does |
|---|---|
| **Pipeline Generation** | Rule-based tech-stack detection (file extensions) → RAG template retrieval (3 Spring Boot + 2 Node.js templates) → LLM-generated GitHub Actions YAML → validation loop → pushed to your repo |
| **Code Review** | PR diff → static-analysis rules (Semgrep/SonarQube-style: syntax, bugs, security, quality, performance) + LLM → severity-bucketed report posted as a PR comment. Also proposes fixes for simple pipeline failures |
| **Log Analysis** | Failed-run logs → parser → error extraction → context/root-cause analysis → LLM failure analysis (root cause, impact, suggested fix, confidence) |
| **Deployment** | Builds/pushes versioned Docker images → deploys to production → health check → **auto-rollback** to the previous version on failure |
| **Security** | Scans repo contents and generated workflows for secrets, injection patterns and insecure configs |

## Architecture

```
React Frontend  ──►  Spring Boot Backend  ──►  FastAPI AI Orchestrator ──► 5 Agents (Gemini 3.6 Flash)
   :5173               :8080  │                  :8001
                              ├─► PostgreSQL (login_db + repo_db)
                              ├─► GitHub App (OAuth, installation tokens, webhooks)
                              └─► ngrok tunnel (optional, for local webhooks)
```

- **Spring Boot** owns: GitHub App auth (RS256 App JWT → installation tokens), OAuth
  login, webhook verification (HMAC-SHA256), PostgreSQL persistence, and all calls to
  the agents service.
- **FastAPI** owns: the AI Orchestrator (Event Handler → Workflow Manager → Task
  Manager with function-calling registry → Retry/Timeout → Result Aggregator → State
  Manager) and the five agents. The backend never talks to the LLM directly.

## Repository layout

```
ai-cicd-platform/
├── .env.example              # copy to .env and fill in
├── docker-compose.yml        # PostgreSQL + agents service
├── agents/                   # FastAPI AI Orchestrator + 5 agents + RAG templates
│   └── app/rag/templates/    # 3 Spring Boot + 2 Node.js workflow templates
├── backend/                  # Spring Boot (GitHub App, webhooks, dual PostgreSQL)
└── frontend/                 # React + Vite dashboard
```

## 1. Create the GitHub App

GitHub → Settings → Developer settings → **GitHub Apps** → **New GitHub App**:

- **Homepage URL**: `http://localhost:5173`
- **Callback URL**: `http://localhost:8080/api/auth/callback`
- **Webhook URL**: `https://<your-ngrok>.ngrok-free.app/api/webhooks/github`
  (fill in after ngrok starts — step 4)
- **Webhook secret**: any random string (goes into `.env`)
- **Permissions** (Repository):
  - Contents: **Read & write** (push the generated workflow)
  - Actions: **Read** (workflow runs, logs)
  - Pull requests: **Read & write** (diff + review comments)
  - Issues: **Read & write** (PR comments use the issues API)
  - Metadata: **Read**
- **Subscribe to events**: `pull_request`, `workflow_run`, `installation`,
  `installation_repositories`
- After creation: note the **App ID**, generate a **private key** (PEM download),
  then **Install App** on your account/repos and note the **installation ID**
  (last number in the URL of the installation page).

## 2. Configure `.env`

```bash
cp .env.example .env
```

Fill in every value from step 1, put the downloaded PEM at
`./github-app-private-key.pem` (or set `GITHUB_PRIVATE_KEY_PATH`), and set
`GEMINI_API_KEY` for the Gemini LLM (get one at [aistudio.google.com](https://aistudio.google.com)).
`LLM_MODEL` defaults to `gemini-3.6-flash` — change it to any Gemini model you prefer
(e.g. `gemini-2.5-pro`, `gemini-2.5-flash-lite`).

> Without `GEMINI_API_KEY` the platform still runs end-to-end in a
> rule-based fallback mode (templates are used as-is, log analysis uses
> signature matching) — handy for demos.

## 3. Start PostgreSQL + the agents service

```bash
docker compose up -d --build      # postgres (creates login_db + repo_db) + agents
```

The agents container mounts `/var/run/docker.sock` so the Deployment Agent can
build/push/run images on the host. Set `DOCKER_REGISTRY_USER` /
`DOCKER_REGISTRY_TOKEN` in `.env` for registry pushes.

## 4. Start the backend

```bash
cd backend
mvn spring-boot:run               # needs JDK 17+
```

With `NGROK_ENABLED=true` (and ngrok installed), a tunnel opens automatically —
copy the printed public URL into your GitHub App's webhook field.

## 5. Start the frontend

```bash
cd frontend
npm install
npm run dev                       # http://localhost:5173
```

## Using the platform

1. **Sign in with GitHub** (OAuth via your GitHub App).
2. Enter your **installation ID** → *List repos* → **Connect** the repo
   (saved to `repo_db`).
3. Click **Generate Pipeline**: the backend fetches the file tree, the
   Pipeline Generation Agent detects the stack (rule-based), the RAG store
   supplies the closest template, Gemini produces the workflow YAML, the
   validator checks it (one auto-fix cycle), it's saved to the DB and pushed
   to `.github/workflows/ai-ci-cd.yml` — GitHub Actions starts running it.
4. **Open a PR** → the Code Review + Security agents post a severity-bucketed
   review comment automatically.
5. **A run fails** → the `workflow_run` webhook triggers the Log Analysis
   Agent; if the failure is simple, the Orchestrator escalates to the Code
   Review Agent to propose a fix. Reports appear under *AI analysis reports*.
6. **A run succeeds** → open *Deployments*: the Deployment Agent builds and
   pushes a versioned image, deploys it, health-checks it, and rolls back to
   the previous version automatically if unhealthy. Manual rollback is one
   click.

## Deployment Agent requirements

- Runs `docker build/push/run` via the host Docker socket (compose default).
- Registry credentials via `DOCKER_REGISTRY_USER` / `DOCKER_REGISTRY_TOKEN`.
- The `workdir` field in the deploy form must point to a checkout of the repo
  containing a `Dockerfile` on the machine running the agents service.
- Health check URL is optional but enables automatic rollback.

## API summary

| Endpoint | Purpose |
|---|---|
| `GET /api/auth/github` → `GET /api/auth/callback` | GitHub OAuth login |
| `POST /api/webhooks/github` | GitHub App webhook receiver |
| `GET /api/repos/available?installationId=` | Repos visible to the installation |
| `POST /api/repos/connect` | Save repo to `repo_db` |
| `POST /api/repos/{id}/generate-pipeline` | Detect stack → RAG → LLM YAML → push to GitHub |
| `GET /api/repos/{id}/pipelines` / `GET /api/pipelines/{id}` | Pipeline history / YAML |
| `GET /api/repos/{id}/runs` | GitHub Actions runs |
| `POST /api/repos/{id}/runs/{runId}/analyze` | Log Analysis on demand |
| `GET /api/repos/{id}/reports` | AI analysis reports |
| `POST /api/repos/{id}/deploy` · `POST /api/repos/{id}/rollback` | Deployment Agent / Rollback Manager |
| `GET /api/health` | Backend + agents service status |
| `POST {agents}/orchestrate` · `GET {agents}/functions` | Direct orchestrator access (function-calling catalogue) |

## Troubleshooting

- **Webhook 401**: `GITHUB_WEBHOOK_SECRET` doesn't match the App's webhook secret.
- **"Failed to load private key"**: wrong `GITHUB_PRIVATE_KEY_PATH` or the PEM is
  not the App's private key.
- **Generate Pipeline returns 400**: the agents service is down — check
  `GET /api/health`.
- **No LLM output**: set `GEMINI_API_KEY`; without it the platform uses its
  rule-based fallbacks.



