# Code Review Agent — Integration & Build Guide

This document describes how to build the **Code Review Agent** as a **separate project** (its own folder/repo). The agent consumes repository data from the CICD Platform backend and produces a structured code review. The **orchestrator** (webhooks, scheduling, posting to GitHub) will be built later — this guide covers only the agent itself.

---

## 1. Scope

| In scope (agent) | Out of scope (orchestrator — later) |
|------------------|-------------------------------------|
| Accept `RepositoryContext` JSON as input | GitHub webhook handling |
| Parse and format repo data for an LLM | Triggering reviews on PR events |
| Run the review prompt against an LLM | Posting PR comments to GitHub |
| Return structured review output (JSON) | Auth token management for users |
| CLI / local testing with saved context files | Scheduling, retries, job queues |

---

## 2. Where the Agent Lives

Create the agent in a **separate folder** next to this platform, for example:

```
Desktop/
├── CICD/                          ← this platform (Spring Boot + React)
│   └── code_review_agent_guide.md ← this document
└── code-review-agent/             ← new agent project
    ├── README.md
    ├── pyproject.toml             # or requirements.txt
    ├── .env.example
    ├── src/
    │   ├── __init__.py
    │   ├── main.py                # CLI entry point
    │   ├── client/
    │   │   └── platform_client.py # fetches context from CICD backend
    │   ├── parser/
    │   │   └── context_parser.py  # RepositoryContext → prompt sections
    │   ├── agent/
    │   │   ├── reviewer.py        # LLM call + response parsing
    │   │   └── prompts.py         # system/user prompt templates
    │   └── models/
    │       ├── repository_context.py
    │       └── review_result.py
    ├── tests/
    │   ├── fixtures/
    │   │   └── sample_context.json
    │   └── test_context_parser.py
    └── output/                    # local review results (gitignored)
```

Use Python or Node/TypeScript — Python is recommended to mirror `pipeline_agent_integration_guide.md`.

---

## 3. How the Agent Gets Repository Data

Today the CICD Platform exposes one endpoint that returns everything the agent needs for a **full-repo review at a specific commit**:

```http
GET http://localhost:8080/api/repos/{owner}/{repo}/context?branch={branch}
Authorization: Bearer <session_token>
Accept: application/json
```

| Parameter | Required | Description |
|-----------|----------|-------------|
| `owner` | Yes | GitHub org or user |
| `repo` | Yes | Repository name |
| `branch` | No | Defaults to repo default branch |

### 3.1 Authentication (for local dev)

1. Start the CICD backend and frontend.
2. Log in with GitHub in the browser.
3. Open DevTools → Application → Local Storage → copy `auth_token`.
4. Pass it as `Authorization: Bearer <token>` from the agent.

The orchestrator will handle auth later. For now, use a token from manual login or save a context JSON file and run the agent offline.

### 3.2 Alternative: Offline fixture (no backend required)

1. Open a repo in the CICD UI → **Inspect Agent Context**.
2. Save the full JSON response to `tests/fixtures/sample_context.json`.
3. Run the agent against that file during development.

This is the recommended workflow while building the agent before the orchestrator exists.

---

## 4. Input Contract — `RepositoryContext`

The backend returns this JSON shape (built by `RepositoryContextBuilder`):

```json
{
  "repository": {
    "githubRepositoryId": 123456,
    "owner": "example-owner",
    "repositoryName": "my-app",
    "branch": "main",
    "commitSha": "a83f91c987654321abcdef0123456789abcdef01"
  },
  "structure": [
    { "path": "src", "name": "src", "type": "directory" },
    { "path": "src/main/java/App.java", "name": "App.java", "type": "text" },
    { "path": "pom.xml", "name": "pom.xml", "type": "text" },
    { "path": "assets/logo.png", "name": "logo.png", "type": "binary" },
    { "path": ".env", "name": ".env", "type": "text" }
  ],
  "files": [
    {
      "path": "src/main/java/App.java",
      "name": "App.java",
      "type": "text",
      "size": 1500,
      "sha": "blob_sha_123",
      "content": "package com.example;\n...",
      "secret": false
    },
    {
      "path": "assets/logo.png",
      "name": "logo.png",
      "type": "binary",
      "size": 18234,
      "sha": "blob_sha_456",
      "content": null,
      "secret": false
    },
    {
      "path": ".env",
      "name": ".env",
      "type": "text",
      "size": 120,
      "sha": "blob_sha_789",
      "content": "[REDACTED: Sensitive file contents masked for security]",
      "secret": true
    }
  ],
  "totalBytes": 4020,
  "truncated": false,
  "message": "Complete repository context successfully constructed from GitHub."
}
```

### 4.1 Field reference

| Field | Type | Agent usage |
|-------|------|-------------|
| `repository.commitSha` | string | Pin the review to an exact commit |
| `repository.owner` / `repositoryName` / `branch` | string | Metadata in review header |
| `structure[]` | array | Build directory tree for LLM context |
| `structure[].type` | `"directory"` \| `"text"` \| `"binary"` | Skip binaries in tree display |
| `files[]` | array | Source code for review |
| `files[].type` | `"text"` \| `"binary"` | Only review `text` files |
| `files[].secret` | boolean | **Never** send secret file contents to LLM |
| `files[].content` | string \| null | File body; `null` for binary |
| `truncated` | boolean | Warn user if review is incomplete |
| `totalBytes` | number | Logging / cost estimation |

### 4.2 Backend safety rules (agent must respect)

The platform already applies these rules. The agent should **not** override them:

- **Secret files** (`.env`, `.pem`, `.key`, `credentials.json`, etc.) → content is redacted, `secret: true`
- **Binary files** → `content: null`, skip content in prompt
- **Size limit** → default 5 MB of text content; `truncated: true` when exceeded

Config on the backend (`application.yml`):

```yaml
app:
  repository-context:
    max-payload-bytes: 5242880   # 5 MB
    include-secrets: false       # keep false in production
```

### 4.3 Files the agent should skip in the prompt

Even when `secret: false`, consider excluding from the LLM prompt:

- `node_modules/`, `vendor/`, `target/`, `dist/`, `build/`
- Lock files if too large (`package-lock.json`, `yarn.lock`) — mention path only
- Generated files (`.min.js`, `.map`)
- Files with `truncated: true` and missing `content`

---

## 5. Agent Responsibilities

```
┌─────────────────────────────────────────────────────────────┐
│                     Code Review Agent                        │
├─────────────────────────────────────────────────────────────┤
│  1. Load input                                               │
│     • From API: GET /api/repos/{owner}/{repo}/context        │
│     • Or from local JSON fixture                             │
│                                                              │
│  2. Parse RepositoryContext                                  │
│     • Validate required fields                               │
│     • Filter reviewable files (text, non-secret, has content)│
│     • Build directory tree string                            │
│     • Build file content blocks                              │
│                                                              │
│  3. Build LLM prompt                                         │
│     • System: reviewer role + output format rules            │
│     • User: repo metadata + tree + file contents             │
│                                                              │
│  4. Call LLM                                                 │
│     • OpenAI / Anthropic / Gemini / local model              │
│                                                              │
│  5. Parse LLM response → ReviewResult JSON                   │
│     • summary, findings[], verdict, metadata                 │
│                                                              │
│  6. Write output                                             │
│     • stdout, file, or return dict for orchestrator later    │
└─────────────────────────────────────────────────────────────┘
```

---

## 6. Parsing Logic (Reference Implementation)

### 6.1 Directory tree formatter

```python
def format_directory_tree(structure: list[dict]) -> str:
    lines = []
    for node in sorted(structure, key=lambda x: x["path"]):
        depth = node["path"].count("/")
        indent = "  " * depth
        suffix = "/" if node["type"] == "directory" else ""
        lines.append(f"{indent}├── {node['name']}{suffix}")
    return "\n".join(lines)
```

### 6.2 Reviewable file filter

```python
SKIP_PREFIXES = ("node_modules/", "vendor/", "target/", "dist/", "build/", ".git/")

def is_reviewable(file: dict) -> bool:
    if file["type"] != "text":
        return False
    if file.get("secret"):
        return False
    if not file.get("content"):
        return False
    path = file["path"]
    return not any(path.startswith(p) for p in SKIP_PREFIXES)

def format_file_contents(files: list[dict]) -> str:
    blocks = []
    for f in files:
        if not is_reviewable(f):
            continue
        blocks.append(
            f"--- START FILE: {f['path']} ({f['size']} bytes) ---\n"
            f"{f['content']}\n"
            f"--- END FILE: {f['path']} ---"
        )
    return "\n\n".join(blocks)
```

### 6.3 Handle truncated context

```python
def build_truncation_warning(ctx: dict) -> str:
    if not ctx.get("truncated"):
        return ""
    return (
        f"WARNING: Repository context was truncated. "
        f"Message: {ctx.get('message', 'unknown')}. "
        f"Review may be incomplete."
    )
```

---

## 7. LLM Prompt Strategy

### 7.1 System prompt (template)

```
You are a senior software engineer performing a code review.

Rules:
- Focus on bugs, security issues, performance problems, and maintainability.
- Ignore style nitpicks unless they hide real bugs.
- Do not invent files or line numbers that are not in the provided content.
- Secret/redacted files were intentionally excluded — do not ask for them.
- Binary files are listed in the tree but not shown — skip them.

Output format:
Return valid JSON only (no markdown fences) matching this schema:
{
  "summary": "1-3 sentence overview",
  "verdict": "approve" | "request_changes" | "comment",
  "findings": [
    {
      "severity": "critical" | "high" | "medium" | "low" | "info",
      "file": "path/to/file",
      "line": 42,
      "title": "Short title",
      "description": "What is wrong and why",
      "suggestion": "How to fix it"
    }
  ],
  "stats": {
    "filesReviewed": 0,
    "findingsCount": 0
  }
}
```

### 7.2 User prompt (template)

```
Review the following repository snapshot.

REPOSITORY:
- Owner: {owner}
- Name: {repositoryName}
- Branch: {branch}
- Commit: {commitSha}

{truncation_warning}

DIRECTORY STRUCTURE:
{directory_tree}

SOURCE FILES:
{file_contents}

Perform a thorough code review and return JSON as specified.
```

### 7.3 Differences from the Pipeline Agent

| Pipeline Agent | Code Review Agent |
|----------------|-------------------|
| Output: GitHub Actions YAML | Output: structured findings JSON |
| Goal: generate CI config | Goal: find bugs, security, quality issues |
| May need build tool detection | Needs line-level references where possible |
| Writes back via `/api/git/push` | Returns review for orchestrator to post later |

---

## 8. Output Contract — `ReviewResult`

The agent must produce JSON the orchestrator can consume later (PR comments, check runs, UI display):

```json
{
  "reviewId": "rev_20260817_abc123",
  "generatedAt": "2026-08-17T11:00:00Z",
  "input": {
    "owner": "example-owner",
    "repositoryName": "my-app",
    "branch": "main",
    "commitSha": "a83f91c987654321abcdef0123456789abcdef01"
  },
  "summary": "The codebase is generally well-structured. Two security concerns and one error-handling gap were found.",
  "verdict": "request_changes",
  "findings": [
    {
      "severity": "high",
      "file": "src/main/java/com/cicd/platform/config/SecurityConfig.java",
      "line": 69,
      "title": "Insecure cookie in production",
      "description": "SameSite=None with secure=false is acceptable for local dev but must not be used in production.",
      "suggestion": "Set useSecureCookie=true and enforce HTTPS in production."
    },
    {
      "severity": "medium",
      "file": "src/main/java/com/cicd/platform/controller/RepoController.java",
      "line": 199,
      "title": "Error message leaks internal details",
      "description": "Exception message is returned directly to the client.",
      "suggestion": "Return a generic error message; log the full exception server-side."
    }
  ],
  "stats": {
    "filesReviewed": 24,
    "filesSkipped": 8,
    "findingsCount": 2,
    "contextTruncated": false
  },
  "model": {
    "provider": "openai",
    "name": "gpt-4o"
  }
}
```

### 8.1 Verdict values

| Verdict | Meaning |
|---------|---------|
| `approve` | No blocking issues |
| `request_changes` | At least one high/critical finding |
| `comment` | Observations only, no blockers |

---

## 9. CLI Interface (Suggested)

Build the agent with a simple CLI for manual testing:

```bash
# Fetch live context from CICD platform and review
python -m src.main review \
  --platform-url http://localhost:8080 \
  --owner my-org \
  --repo my-app \
  --branch main \
  --token "$CICD_AUTH_TOKEN" \
  --output output/review.json

# Review from saved fixture (no backend needed)
python -m src.main review \
  --context-file tests/fixtures/sample_context.json \
  --output output/review.json
```

### 9.1 Environment variables (`.env.example`)

```bash
# CICD Platform (optional — only for live fetch mode)
CICD_PLATFORM_URL=http://localhost:8080
CICD_AUTH_TOKEN=

# LLM provider (pick one)
OPENAI_API_KEY=
ANTHROPIC_API_KEY=
GOOGLE_API_KEY=

# Agent config
REVIEW_MODEL=gpt-4o
REVIEW_MAX_FILES=50
REVIEW_MAX_CHARS=120000
```

---

## 10. Platform Client (Minimal)

```python
import requests

class PlatformClient:
    def __init__(self, base_url: str, token: str):
        self.base_url = base_url.rstrip("/")
        self.token = token

    def fetch_repository_context(self, owner: str, repo: str, branch: str | None = None) -> dict:
        url = f"{self.base_url}/api/repos/{owner}/{repo}/context"
        params = {"branch": branch} if branch else {}
        response = requests.get(
            url,
            params=params,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/json",
            },
            timeout=120,
        )
        response.raise_for_status()
        return response.json()
```

---

## 11. Agent Entry Point (Minimal)

```python
import json
import sys
from pathlib import Path

from client.platform_client import PlatformClient
from parser.context_parser import build_review_prompt
from agent.reviewer import run_review

def main():
    # Load context from file or API (simplified)
    if "--context-file" in sys.argv:
        path = Path(sys.argv[sys.argv.index("--context-file") + 1])
        context = json.loads(path.read_text())
    else:
        client = PlatformClient(
            base_url=os.environ["CICD_PLATFORM_URL"],
            token=os.environ["CICD_AUTH_TOKEN"],
        )
        context = client.fetch_repository_context("owner", "repo", "main")

    prompt = build_review_prompt(context)
    result = run_review(prompt, context)
    print(json.dumps(result, indent=2))

if __name__ == "__main__":
    main()
```

---

## 12. Testing Checklist

Before connecting the orchestrator, verify the agent standalone:

- [ ] Parses a real `RepositoryContext` JSON from **Inspect Agent Context** in the UI
- [ ] Skips `secret: true` files (never sends redacted content back to LLM as if real)
- [ ] Skips binary files (`content: null`)
- [ ] Handles `truncated: true` with a warning in the prompt and `stats.contextTruncated`
- [ ] Returns valid `ReviewResult` JSON every time (parse LLM output with retry on invalid JSON)
- [ ] Works with `--context-file` with no backend running
- [ ] Works with live API + Bearer token against `localhost:8080`
- [ ] Respects `REVIEW_MAX_FILES` / char limits for large repos

---

## 13. Future: PR Diff Context (Orchestrator Phase)

When the orchestrator is built, the backend will likely add a PR-specific endpoint:

```http
GET /api/repos/{owner}/{repo}/pulls/{prNumber}/review-context
```

That payload will send **changed files + diffs** instead of the full repo. Design the agent with two input modes:

| Mode | Input | When |
|------|-------|------|
| `full` | `RepositoryContext` | Manual review, first commit, small repos |
| `diff` | `PullRequestReviewContext` (future) | PR webhook reviews |

For now, implement **`full` mode only**. Keep `context_parser.py` and `reviewer.py` separate so adding `diff` mode later is a new parser, not a rewrite.

---

## 14. Security Notes

1. **Never log** `CICD_AUTH_TOKEN` or LLM API keys.
2. **Never send** files where `secret: true` to the LLM — skip entirely, mention only the path.
3. **Do not set** `include-secrets: true` on the backend in production.
4. Store review output locally during dev; treat it as potentially sensitive (it contains source code).
5. The agent folder should have its own `.gitignore` for `.env`, `output/`, and saved context fixtures with real code.

---

## 15. Relationship to Other Docs

| Document | Purpose |
|----------|---------|
| `code_review_agent_guide.md` (this file) | Build the standalone code review agent |
| `pipeline_agent_integration_guide.md` | Pipeline generation agent + orchestrator flow |
| `implementation_plan.md` | Original platform architecture (OAuth, import, push) |

---

## 16. Quick Start Summary

1. Create `code-review-agent/` folder (separate from `CICD/`).
2. In the CICD UI, open a repo → **Inspect Agent Context** → save JSON to a fixture file.
3. Implement: parser → prompt → LLM call → `ReviewResult` JSON.
4. Test with `--context-file` until output is stable.
5. Optionally test live fetch via `GET /api/repos/{owner}/{repo}/context`.
6. Hand off `ReviewResult` JSON to the orchestrator when it is built.

The orchestrator will later: trigger the agent, pass context (or let the agent fetch it), and post findings to GitHub. **The agent's only job is: input context → output review JSON.**
