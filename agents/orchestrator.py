"""AI Orchestrator — Multi-Agent Workflow Engine for DeployHub.

Chains the 4 specialized agents into interconnected workflows matching the Architecture Flowchart:

  GitHub Events / User Trigger
             ↓
       AI Orchestrator
             ↓
  ┌────────────────────────┬─────────────────────────┬────────────────────────┐
  │                        │                         │                        │
Code Review Agent    Pipeline Gen Agent       Log Analysis Agent      Deployment Agent
 (PR Diff + Semgrep    (Repo Context →          (Failure Logs →          (Docker Deploy →
  + SonarQube + LLM)     YAML Generation)        Root Cause + Fix)        Health Check →
                                                         │                 Rollback/History)
                                                         ↓                        │
                                                Self-Healing Loop                 │
                                            (Pass fix to Pipeline Gen)            │
                                                         │                        │
                                                         └────────────────────────┘
                                                                     │
                                                                     ↓
                                                              React Dashboard
"""

from __future__ import annotations
import logging
import time
import uuid
from typing import Any, Callable

# Agent imports
from pipeline_agent.analyzer import analyze_repository
from pipeline_agent.planner import plan_pipeline
from pipeline_agent.generator import generate_pipeline_yaml, remediate_pipeline_yaml
from pipeline_agent.validator import validate_pipeline

from code_review_agent.reviewer import review_repository_context, review_pull_request_diff
from code_review_agent.formatter import format_pr_comment

from log_analysis_agent.analyzer import analyze_logs

from deployment_agent.executor import execute_deployment, manual_rollback, get_version_history
from deployment_agent.planner import plan_deployment

log = logging.getLogger(__name__)


class AIOrchestrator:
    """Central coordinator for chained multi-agent workflows."""

    def __init__(self) -> None:
        self._workflows: dict[str, Callable[[dict[str, Any]], dict[str, Any]]] = {
            "review_pull_request": self._workflow_review_pull_request,
            "review_repository": self._workflow_review_repository,
            "generate_pipeline": self._workflow_generate_pipeline,
            "analyze_failure": self._workflow_analyze_failure,
            "pipeline_failure_remediation": self._workflow_pipeline_failure_remediation,
            "deploy": self._workflow_deploy,
            "rollback": self._workflow_rollback,
            "full_pipeline_lifecycle": self._workflow_full_pipeline_lifecycle,
        }

    def list_workflows(self) -> list[str]:
        return list(self._workflows.keys())

    def list_agents(self) -> list[dict[str, str]]:
        return [
            {
                "name": "code_review",
                "role": "Reviews code diffs & repo context using Semgrep, SonarQube, and Gemini LLM",
                "inputs": "diff, context, pr_number",
            },
            {
                "name": "pipeline_generation",
                "role": "Detects tech stack, plans CI/CD stages, and generates validated GitHub Actions YAML",
                "inputs": "context, tech, plan",
            },
            {
                "name": "log_analysis",
                "role": "Parses failure logs, diagnoses root causes, and recommends actionable fixes",
                "inputs": "log_text",
            },
            {
                "name": "deployment",
                "role": "Manages Docker image lifecycle, production deployments, health checks, version history, and rollbacks",
                "inputs": "owner, repo, commit_sha, environment",
            },
        ]

    def execute_workflow(self, workflow_name: str, payload: dict[str, Any]) -> dict[str, Any]:
        """Execute a named multi-agent workflow."""
        if workflow_name not in self._workflows:
            raise ValueError(f"Unknown workflow '{workflow_name}'. Available: {self.list_workflows()}")

        start_time = time.time()
        log.info("Starting multi-agent workflow '%s'", workflow_name)

        handler = self._workflows[workflow_name]
        result = handler(payload)

        elapsed_ms = int((time.time() - start_time) * 1000)
        result["workflow_execution"] = {
            "workflow": workflow_name,
            "execution_time_ms": elapsed_ms,
            "timestamp": time.time(),
        }
        return result

    # ── WORKFLOW 1: Code Review for Pull Request ───────────────────────────
    def _workflow_review_pull_request(self, payload: dict[str, Any]) -> dict[str, Any]:
        """PR event -> Code Review Agent (diff + static analysis + LLM) -> Review Result + Comment."""
        diff_text = payload.get("diff", "")
        pr_meta = {
            "owner": payload.get("owner", "unknown"),
            "repo": payload.get("repo", "unknown"),
            "pr_number": payload.get("pr_number"),
            "title": payload.get("title", "Pull Request"),
            "author": payload.get("author", "unknown"),
        }

        review = review_pull_request_diff(diff_text, pr_meta)
        return {
            "agent": "code_review",
            "review": review.model_dump(),
            "pr_comment_markdown": review.pr_comment_markdown,
            "verdict": review.verdict,
            "findings_count": review.stats.findings_count,
        }

    # ── WORKFLOW 2: Code Review for Repository Context ──────────────────────
    def _workflow_review_repository(self, payload: dict[str, Any]) -> dict[str, Any]:
        context = payload.get("context", {})
        review = review_repository_context(context)
        return {
            "agent": "code_review",
            "review": review.model_dump(),
            "verdict": review.verdict,
            "findings_count": review.stats.findings_count,
        }

    # ── WORKFLOW 3: Generate CI/CD Pipeline ─────────────────────────────────
    def _workflow_generate_pipeline(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Repo Event -> Pipeline Gen Agent -> YAML -> Validation -> Commit ready."""
        context = payload.get("context", {})
        tech = analyze_repository(context)
        plan = plan_pipeline(tech)
        yaml_content = generate_pipeline_yaml(context, plan, tech)
        validation = validate_pipeline(yaml_content)

        return {
            "agent": "pipeline_generation",
            "yaml_content": yaml_content,
            "file_path": ".github/workflows/ci.yml",
            "technology": tech,
            "validation": validation,
            "status": "success" if validation["valid"] else "warning",
        }

    # ── WORKFLOW 4: Analyze Pipeline Failure ────────────────────────────────
    def _workflow_analyze_failure(self, payload: dict[str, Any]) -> dict[str, Any]:
        """GitHub Actions Fail -> Log Analysis Agent (3 steps) -> Failure Analysis."""
        log_text = payload.get("log_text", "")
        analysis = analyze_logs(log_text)
        return {
            "agent": "log_analysis",
            "analysis": analysis.model_dump(),
            "root_causes": [rc.model_dump() for rc in analysis.root_causes],
            "suggested_fixes": [sf.model_dump() for sf in analysis.suggested_fixes],
            "status": analysis.status,
        }

    # ── WORKFLOW 5: Multi-Agent Self-Healing Remediation Chain ───────────────
    def _workflow_pipeline_failure_remediation(self, payload: dict[str, Any]) -> dict[str, Any]:
        """
        Flowchart Multi-Agent Loop:
        Pipeline FAILED → Log Analysis Agent (Step 1: Analyse, Step 2: Root Cause, Step 3: Suggest Fix)
                        → AI Orchestrator bridges output
                        → Pipeline Generation Agent (Heals YAML)
        """
        log_text = payload.get("log_text", "")
        failed_yaml = payload.get("failed_yaml", "")

        # Step 1-3: Run Log Analysis Agent
        analysis = analyze_logs(log_text)

        # Summarize root causes and fixes for the pipeline generator
        rc_summary = "; ".join(f"[{rc.category}] {rc.description}" for rc in analysis.root_causes) or "Unknown failure"
        fix_summary = "; ".join(f"{sf.title}: {sf.description}" for sf in analysis.suggested_fixes) or "Fix pipeline error"

        # Step 4: Pass diagnosis directly to Pipeline Generation Agent to self-heal
        healed_yaml = remediate_pipeline_yaml(
            failed_yaml=failed_yaml,
            error_logs=log_text,
            root_cause=rc_summary,
            suggested_fix=fix_summary,
        )

        validation = validate_pipeline(healed_yaml)

        return {
            "chain": ["log_analysis", "pipeline_generation"],
            "failure_analysis": analysis.model_dump(),
            "root_cause": rc_summary,
            "suggested_fix": fix_summary,
            "healed_yaml": healed_yaml,
            "file_path": ".github/workflows/ci.yml",
            "validation": validation,
            "auto_remediated": validation["valid"],
        }

    # ── WORKFLOW 6: Production Deployment & Health Lifecycle ────────────────
    def _workflow_deploy(self, payload: dict[str, Any]) -> dict[str, Any]:
        """
        Flowchart Deployment Loop:
        Pipeline SUCCESS → Deployment Agent:
          Step 1: Build / Use Image -> Docker Image
          Step 2: Deploy -> Production Environment
          Step 3: Health Check
          Step 4: If healthy -> Version History (Current Version & Previous Versions)
          Step 5: If failed -> Rollback -> Version History (Restore Previous Version)
        """
        owner = payload.get("owner", "unknown")
        repo = payload.get("repo", "unknown")
        commit_sha = payload.get("commit_sha", "unknown")
        env = payload.get("environment", "production")
        image_tag = payload.get("image_tag")
        simulate_failure = payload.get("simulate_health_failure", False)

        execution = execute_deployment(
            owner=owner,
            repo=repo,
            commit_sha=commit_sha,
            environment=env,
            image_tag=image_tag,
            simulate_health_failure=simulate_failure,
        )

        history = get_version_history(owner, repo)

        return {
            "agent": "deployment",
            "deployment": execution.model_dump(),
            "version_history": history.model_dump(),
            "status": execution.status,
            "health_check_status": execution.health_check_status,
            "rolled_back": execution.rolled_back,
        }

    # ── WORKFLOW 7: Manual Rollback ─────────────────────────────────────────
    def _workflow_rollback(self, payload: dict[str, Any]) -> dict[str, Any]:
        owner = payload.get("owner", "unknown")
        repo = payload.get("repo", "unknown")
        target_version = payload.get("target_version")

        execution = manual_rollback(owner, repo, target_version)
        history = get_version_history(owner, repo)

        return {
            "agent": "deployment",
            "rollback_result": execution.model_dump(),
            "version_history": history.model_dump(),
        }

    # ── WORKFLOW 8: Full End-to-End Pipeline Lifecycle ──────────────────────
    def _workflow_full_pipeline_lifecycle(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Runs pipeline generation first, plans deployment, and returns full blueprint."""
        context = payload.get("context", {})
        pipeline_res = self._workflow_generate_pipeline({"context": context})
        deployment_plan = plan_deployment(context)

        return {
            "chain": ["pipeline_generation", "deployment"],
            "pipeline": pipeline_res,
            "deployment_plan": deployment_plan.model_dump(),
        }


# Singleton orchestrator instance
orchestrator = AIOrchestrator()
