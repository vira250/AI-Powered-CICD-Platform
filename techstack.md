# AI CI/CD Platform — Tech Stack & Use Cases

An overview of the use cases, technical architecture, and SDK/library dependencies for the AI-Powered CI/CD and DevOps Orchestration Platform.

---

## 1. Core Use Cases

| ID | Use Case | Description | Trigger / Mechanism | Key Agents & Components |
|:---|:---|:---|:---|:---|
| **UC-01** | **Automated CI/CD Pipeline Generation** | Inspects repository structure, detects language/framework stack, retrieves matching RAG templates, and generates valid GitHub Actions YAML workflows. | User triggers from Dashboard (`POST /api/repos/{id}/generate-pipeline`) | Pipeline Generation Agent, Gemini 3.5/3.6 Flash, RAG Templates |
| **UC-02** | **Automated PR Code Review** | Performs static analysis and LLM-assisted code review on Pull Request diffs (detecting bugs, security issues, and style violations) and posts review comments to the PR. | GitHub `pull_request` Webhook | Code Review Agent, Security Agent, GitHub REST API |
| **UC-03** | **Security & Secret Scanning** | Scans repository source files and generated workflow YAMLs for leaked secrets, API keys, injection vulnerabilities, and dangerous permissions. | Triggered during PR review & Pipeline generation | Security Agent, Regex Rules, LLM Analysis |
| **UC-04** | **Intelligent Failure Log Analysis** | Analyzes error logs from failed CI/CD workflow runs, determines root causes with confidence scoring, and recommends actionable fixes. | GitHub `workflow_run` Webhook / On-demand API | Log Analysis Agent, Error Signature Parser, Gemini LLM |
| **UC-05** | **Self-Healing / Auto-Remediation** | Automatically fixes broken CI/CD pipeline YAML configurations when a workflow failure is caused by pipeline syntax/config errors, committing fixes directly. | Orchestrator escalation upon pipeline failure | Orchestrator, Code Review Agent, GitHub Commit API |
| **UC-06** | **Container Deployment & Health Check** | Coordinates Docker image builds, pushes to registry, and deploys containerized applications with automated post-deployment health verification. | User triggers deploy from Dashboard | Deployment Agent, Docker Engine API / CLI |
| **UC-07** | **Automated Rollback on Failure** | Automatically rolls back to the previous stable release if post-deployment health checks fail or an unhealthy status is detected. | Unhealthy HTTP status / Manual 1-click rollback | Rollback Manager, Deployment Agent, PostgreSQL State |
| **UC-08** | **Multi-Repository DevOps Dashboard** | Central web dashboard for monitoring GitHub installations, connected repositories, build histories, AI review reports, and deployment status. | Web UI access | React Frontend, Spring Boot REST Backend |

---

## 2. Tech Stack, SDKs & Libraries

### 2.1 AI & Agent Service Layer (Python / FastAPI)

| ID | Technology / SDK | Version / Spec | Purpose & Usage | Reference File |
|:---|:---|:---|:---|:---|
| **SDK-01** | `openai` Python SDK | `>=1.59.8` | Client SDK used to communicate with **Google Gemini Models** (`gemini-3.5-flash`, `gemini-3.6-flash`, `gemini-2.5-pro`) via Google AI's OpenAI-compatible endpoint with automatic quota failover. | [agents/app/llm.py](file:///e:/Professional/College/Github/ai-cicd-platform/agents/app/llm.py) |
| **SDK-02** | `fastapi` | `>=0.115.6` | High-performance asynchronous web framework exposing AI agent endpoints and the Orchestrator service. | [agents/app/main.py](file:///e:/Professional/College/Github/ai-cicd-platform/agents/app/main.py) |
| **SDK-03** | `uvicorn[standard]` | `>=0.34.0` | Production ASGI web server running the FastAPI agents service. | [agents/Dockerfile](file:///e:/Professional/College/Github/ai-cicd-platform/agents/Dockerfile) |
| **SDK-04** | `pydantic` & `pydantic-settings` | `>=2.10.5` | Strict data validation, request/response schema modeling, and type-safe environment configuration. | [agents/app/config.py](file:///e:/Professional/College/Github/ai-cicd-platform/agents/app/config.py) |
| **SDK-05** | `httpx` | `>=0.28.1` | Asynchronous HTTP client used for outbound API calls and internal service communication. | [agents/requirements.txt](file:///e:/Professional/College/Github/ai-cicd-platform/agents/requirements.txt) |
| **SDK-06** | `pyyaml` | `>=6.0.2` | Parsing, validating, and formatting GitHub Actions workflow YAML configurations. | [agents/app/agents/pipeline_generation.py](file:///e:/Professional/College/Github/ai-cicd-platform/agents/app/agents/pipeline_generation.py) |
| **SDK-07** | `Docker CLI / Engine Socket` | Host mount (`/var/run/docker.sock`) | Direct container orchestration for building, tagging, pushing, and running container images. | [agents/app/agents/deployment.py](file:///e:/Professional/College/Github/ai-cicd-platform/agents/app/agents/deployment.py) |

---

### 2.2 Backend Service Layer (Java / Spring Boot)

| ID | Technology / SDK | Version / Spec | Purpose & Usage | Reference File |
|:---|:---|:---|:---|:---|
| **SDK-08** | `Spring Boot Starter Web` | `3.4.1` | Core REST API framework, controllers, exception handlers, and routing. | [backend/pom.xml](file:///e:/Professional/College/Github/ai-cicd-platform/backend/pom.xml) |
| **SDK-09** | `Spring Boot Starter Data JPA` | `3.4.1` | Object-relational mapping (Hibernate/JPA) for dual PostgreSQL databases (`login_db`, `repo_db`). | [backend/pom.xml](file:///e:/Professional/College/Github/ai-cicd-platform/backend/pom.xml) |
| **SDK-10** | `Spring Boot Starter Security` | `3.4.1` | Security filters, session validation, CORS configuration, and endpoint authorization. | [backend/pom.xml](file:///e:/Professional/College/Github/ai-cicd-platform/backend/pom.xml) |
| **SDK-11** | `JJWT (Java JWT)` | `0.12.6` | Generates RS256 JWT tokens for GitHub App authentication and HS256 JWT tokens for user authentication sessions. | [backend/src/main/java/com/aicicd/platform/github/GitHubAppService.java](file:///e:/Professional/College/Github/ai-cicd-platform/backend/src/main/java/com/aicicd/platform/github/GitHubAppService.java) |
| **SDK-12** | `BouncyCastle (bcpkix-jdk18on)` | `1.79` | Cryptographic SDK for parsing and loading PKCS#1 PEM private keys used by the GitHub App. | [backend/src/main/java/com/aicicd/platform/github/GitHubAppService.java](file:///e:/Professional/College/Github/ai-cicd-platform/backend/src/main/java/com/aicicd/platform/github/GitHubAppService.java) |
| **SDK-13** | `Spring RestClient` | `Spring 6+` | Modern synchronous HTTP client used for direct, lightweight REST interaction with GitHub API and the Agents service. | [backend/src/main/java/com/aicicd/platform/github/GitHubApiClient.java](file:///e:/Professional/College/Github/ai-cicd-platform/backend/src/main/java/com/aicicd/platform/github/GitHubApiClient.java) |
| **SDK-14** | `PostgreSQL JDBC Driver` | `org.postgresql` | Database driver enabling connection pooling and transactions for PostgreSQL persistence. | [backend/pom.xml](file:///e:/Professional/College/Github/ai-cicd-platform/backend/pom.xml) |

---

### 2.3 Frontend Dashboard Layer (React / Vite)

| ID | Technology / SDK | Version / Spec | Purpose & Usage | Reference File |
|:---|:---|:---|:---|:---|
| **SDK-15** | `React` & `React DOM` | `^18.3.1` | Core UI library for declarative component architecture and interactive state management. | [frontend/package.json](file:///e:/Professional/College/Github/ai-cicd-platform/frontend/package.json) |
| **SDK-16** | `React Router DOM` | `^6.28.1` | Client-side routing across dashboard pages (Auth, Repos, Pipelines, Deployments, Reports). | [frontend/package.json](file:///e:/Professional/College/Github/ai-cicd-platform/frontend/package.json) |
| **SDK-17** | `Axios` | `^1.7.9` | HTTP client for making authenticated API requests with interceptors to the Spring Boot backend. | [frontend/package.json](file:///e:/Professional/College/Github/ai-cicd-platform/frontend/package.json) |
| **SDK-18** | `Vite` | `^6.0.7` | Next-generation frontend build tool and local development server. | [frontend/vite.config.js](file:///e:/Professional/College/Github/ai-cicd-platform/frontend/vite.config.js) |

---

## 3. Autonomous AI Agents Summary

| ID | Agent Name | Core Mechanism / Engine | Primary Responsibilities |
|:---|:---|:---|:---|
| **AGT-01** | **Pipeline Generation Agent** | Rule-based stack detector + RAG template retrieval + Gemini 3.5/3.6 Flash | Analyzes repo files, retrieves matching CI/CD template, generates YAML, and verifies syntax before commit. |
| **AGT-02** | **Code Review Agent** | Static rules (Semgrep/SonarQube style) + Gemini LLM | Reviews PR diffs, detects bugs, syntax errors, and quality issues, and formats severity-bucketed PR comments. |
| **AGT-03** | **Security Agent** | Heuristic pattern matching + LLM vulnerability detection | Audits files and workflows for hardcoded credentials, secret leakage, script injection, and over-privileged permissions. |
| **AGT-04** | **Log Analysis Agent** | Regex log parsers + Gemini Root Cause Analysis (RCA) | Analyzes failed build/test logs, extracts stack traces, pinpoints root cause, and provides fixes with confidence scores. |
| **AGT-05** | **Deployment Agent** | Docker Host Socket API + Container Lifecycle Management | Builds images, tags versions, pushes to container registry, runs health probes, and manages automatic rollbacks. |
