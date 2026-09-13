# Log Analyzer

The Log Analyzer has two supported inputs:

1. Paste CI/CD output into `/logs` and choose **Analyze Logs**.
2. Select an imported repository, workflow run, and job. The backend retrieves the GitHub Actions job archive and sends a bounded, redacted log to the Python agent.

## Flow

React calls Spring Boot under `/api/agents/logs`. Spring Boot uses the authenticated GitHub App or OAuth token to list workflow runs/jobs and download the selected job archive. The Python agent preprocesses the log, extracts errors and warnings deterministically, redacts common credentials, and asks the configured LLM for root-cause and fix suggestions. The response is normalized into the Pydantic result model before it reaches the UI.

## Configuration

Copy `.env.example` values into the environment used by the backend and agent. `GOOGLE_API_KEY` is optional because the Python agent has a deterministic fallback, but real AI reasoning requires it. GitHub Actions log retrieval requires an authenticated GitHub App installation or OAuth token with workflow/actions read access.

## Demo

Start PostgreSQL, the Spring Boot backend, the Python agent on port `8001`, and the Vite frontend. Import a repository that has at least one GitHub Actions run, open **Log Analysis**, select the repository/run/job, and analyze it. The **Load Sample Error Logs** button remains available for a credential-free UI demo.

## API endpoints

- `GET /api/agents/logs/runs?owner={owner}&repo={repo}`
- `GET /api/agents/logs/runs/{runId}/jobs?owner={owner}&repo={repo}`
- `POST /api/agents/logs/analyze-job` with `{ "owner", "repo", "job_id" }`
- `POST /api/agents/logs/analyze` with `{ "log_text" }`