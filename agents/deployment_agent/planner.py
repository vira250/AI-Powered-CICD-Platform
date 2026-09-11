"""
Deployment Planner — generates Dockerfile, docker-compose, deployment strategy,
and health check configuration based on detected technologies.
"""

import uuid
from datetime import datetime, timezone
from typing import Any

from llm_client import call_llm, extract_json_from_response
from deployment_agent.models import (
    DeploymentPlan, EnvironmentConfig, HealthCheckConfig, RollbackStrategy
)
from pipeline_agent.analyzer import analyze_repository
from config import LLM_MODEL


SYSTEM_PROMPT = """You are a Senior DevOps Engineer specializing in containerized deployments.
Generate production-ready Dockerfile and docker-compose.yml configurations.

Output valid JSON only with this schema:
{
  "dockerfile": "Complete Dockerfile content as a string",
  "docker_compose": "Complete docker-compose.yml content as a string",
  "deployment_strategy": "rolling" | "blue_green" | "canary" | "recreate",
  "port": 8080,
  "health_endpoint": "/health",
  "env_vars": {"KEY": "description of value"},
  "notes": ["Important deployment notes"]
}

Rules:
- Use multi-stage builds for compiled languages (Java, Go, Rust, TypeScript).
- Use slim/alpine base images where possible.
- Include proper HEALTHCHECK instructions in the Dockerfile.
- Set up non-root user for security.
- Include .dockerignore recommendations in notes.
- Use environment variables for configuration, never hardcode secrets.
"""


def plan_deployment(context: dict) -> DeploymentPlan:
    """
    Generate a deployment plan including Dockerfile and docker-compose
    based on the repository context.
    """
    tech = analyze_repository(context)
    repo_info = context.get("repository", {})

    tree_str = _format_short_tree(context.get("structure", []))
    key_files = _get_key_file_contents(context.get("files", []))

    user_prompt = f"""Generate a deployment configuration for this application.

REPOSITORY:
- Name: {repo_info.get('repositoryName', 'app')}
- Owner: {repo_info.get('owner', 'unknown')}

TECHNOLOGY:
- Language: {tech.get('language', 'Unknown')}
- Framework: {tech.get('framework', 'None')}
- Build Tool: {tech.get('build_tool', 'Unknown')}
- Has Existing Docker: {tech.get('has_docker', False)}
- Java Version: {tech.get('java_version', 'N/A')}
- Node Version: {tech.get('node_version', 'N/A')}
- Python Version: {tech.get('python_version', 'N/A')}

DIRECTORY STRUCTURE (abbreviated):
{tree_str}

KEY FILES:
{key_files}

Generate a production-ready Dockerfile (multi-stage if applicable) and docker-compose.yml.
Return JSON as specified in the system prompt.
"""

    raw_response = call_llm(SYSTEM_PROMPT, user_prompt, expect_json=True)
    result = extract_json_from_response(raw_response)

    plan_id = f"deploy_{uuid.uuid4().hex[:8]}"
    port = result.get("port", 8080)

    return DeploymentPlan(
        plan_id=plan_id,
        generated_at=datetime.now(timezone.utc).isoformat(),
        repository={
            "owner": repo_info.get("owner", "unknown"),
            "name": repo_info.get("repositoryName", "app"),
            "branch": repo_info.get("branch", "main"),
        },
        technology=tech,
        dockerfile=result.get("dockerfile", _fallback_dockerfile(tech)),
        docker_compose=result.get("docker_compose", _fallback_compose(tech, port)),
        deployment_strategy=result.get("deployment_strategy", "rolling"),
        environments=[
            EnvironmentConfig(
                name="staging",
                port=port,
                env_vars=result.get("env_vars", {}),
                health_check=HealthCheckConfig(
                    endpoint=result.get("health_endpoint", "/health"),
                ),
            ),
            EnvironmentConfig(
                name="production",
                port=port,
                env_vars=result.get("env_vars", {}),
                health_check=HealthCheckConfig(
                    endpoint=result.get("health_endpoint", "/health"),
                ),
            ),
        ],
        rollback=RollbackStrategy(enabled=True),
        notes=result.get("notes", []),
        model={"provider": "google", "name": LLM_MODEL},
    )


def _format_short_tree(structure: list[dict]) -> str:
    lines = []
    for node in sorted(structure, key=lambda x: x.get("path", ""))[:60]:
        suffix = "/" if node.get("type") == "directory" else ""
        lines.append(f"  {node.get('path', '')}{suffix}")
    return "\n".join(lines)


def _get_key_file_contents(files: list[dict]) -> str:
    relevant = {"pom.xml", "build.gradle", "package.json", "requirements.txt",
                 "pyproject.toml", "go.mod", "Cargo.toml", "Dockerfile",
                 "docker-compose.yml", "docker-compose.yaml", "Procfile"}
    blocks = []
    for f in files:
        if f.get("name") in relevant and f.get("content") and not f.get("secret"):
            content = f["content"][:2000]
            blocks.append(f"--- {f['path']} ---\n{content}")
    return "\n\n".join(blocks) if blocks else "(No key files found)"


def _fallback_dockerfile(tech: dict) -> str:
    lang = tech.get("language", "")
    if lang == "Java":
        return "FROM eclipse-temurin:17-jre-alpine\nWORKDIR /app\nCOPY target/*.jar app.jar\nEXPOSE 8080\nENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]"
    if lang in ("JavaScript", "TypeScript"):
        return "FROM node:20-alpine\nWORKDIR /app\nCOPY package*.json ./\nRUN npm ci --production\nCOPY . .\nEXPOSE 3000\nCMD [\"node\", \"index.js\"]"
    if lang == "Python":
        return "FROM python:3.12-slim\nWORKDIR /app\nCOPY requirements.txt .\nRUN pip install --no-cache-dir -r requirements.txt\nCOPY . .\nEXPOSE 8000\nCMD [\"python\", \"main.py\"]"
    return "FROM ubuntu:22.04\nWORKDIR /app\nCOPY . .\nEXPOSE 8080\nCMD [\"./start.sh\"]"


def _fallback_compose(tech: dict, port: int) -> str:
    return f"""version: '3.8'
services:
  app:
    build: .
    ports:
      - "{port}:{port}"
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:{port}/health"]
      interval: 30s
      timeout: 10s
      retries: 3
"""
