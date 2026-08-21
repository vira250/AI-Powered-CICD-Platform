"""AI Orchestrator.

Implements the Orchestrator architecture:
  Incoming Event -> Event Handler -> Workflow Manager -> Task Manager
  -> dispatch to one of the registered agents (function calling)
  -> Result Aggregator -> State Manager (in-memory + handed back to the
     Spring Boot backend for persistence in PostgreSQL).

The Task Manager exposes each agent as a callable "function" (name,
description, input schema) — mirroring LLM function-calling — and supports
chained workflows, e.g.:

  pipeline_failed  -> log_analysis -> (simple fix?) -> code_review(mode=fix)
  pr_opened        -> code_review + security (parallel fan-out)
  pipeline_success -> deployment
"""
from __future__ import annotations

import time
import uuid
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeout
from typing import Any, Callable

from .agents import (CodeReviewAgent, DeploymentAgent, LogAnalysisAgent,
                     PipelineGenerationAgent, SecurityAgent)

TASK_TIMEOUT_SECONDS = 300
MAX_RETRIES = 2


class TaskManager:
    """Function-calling registry: maps a function name to an agent call."""

    def __init__(self) -> None:
        self._agents = {
            "pipeline_generation": PipelineGenerationAgent(),
            "log_analysis": LogAnalysisAgent(),
            "code_review": CodeReviewAgent(),
            "security": SecurityAgent(),
            "deployment": DeploymentAgent(),
        }

    # -- function-calling style schema, surfaced at /functions -------------
    def functions(self) -> list[dict[str, Any]]:
        schemas = {
            "pipeline_generation": {"files": "list[str] — repo file paths"},
            "log_analysis": {"logs": "str", "job_name": "str?"},
            "code_review": {"mode": "review|fix", "diff": "str",
                            "file_path": "str?", "content": "str?",
                            "root_cause": "str?", "suggested_fix": "str?"},
            "security": {"files": "list[{path, content}]"},
            "deployment": {"action": "deploy|rollback", "repo": "str",
                           "version": "str?", "workdir": "str?",
                           "health_url": "str?", "previous_image": "str?"},
        }
        return [{"name": a.name, "description": a.description,
                 "parameters": schemas.get(a.name, {})}
                for a in self._agents.values()]

    def has(self, name: str) -> bool:
        return name in self._agents

    # -- Retry & Timeout Manager -------------------------------------------
    def call(self, name: str, payload: dict[str, Any]) -> dict[str, Any]:
        if name not in self._agents:
            return {"error": f"unknown agent '{name}'",
                    "available": list(self._agents)}
        agent = self._agents[name]
        last: dict[str, Any] = {}
        for attempt in range(1, MAX_RETRIES + 2):
            with ThreadPoolExecutor(max_workers=1) as pool:
                future = pool.submit(agent.run, payload)
                try:
                    return future.result(timeout=TASK_TIMEOUT_SECONDS)
                except FuturesTimeout:
                    last = {"error": f"agent '{name}' timed out",
                            "attempt": attempt}
                except Exception as exc:  # noqa: BLE001 - surfaced to caller
                    last = {"error": f"{type(exc).__name__}: {exc}",
                            "attempt": attempt}
        return last


class StateManager:
    """Lightweight in-memory state store for running/completed tasks."""

    def __init__(self) -> None:
        self._tasks: dict[str, dict[str, Any]] = {}

    def start(self, event: str) -> str:
        task_id = uuid.uuid4().hex[:12]
        self._tasks[task_id] = {"task_id": task_id, "event": event,
                                "status": "running", "steps": [],
                                "started_at": time.time()}
        return task_id

    def record(self, task_id: str, step: str, result: dict[str, Any]) -> None:
        self._tasks[task_id]["steps"].append({"step": step, "result": result})

    def finish(self, task_id: str, status: str) -> dict[str, Any]:
        self._tasks[task_id]["status"] = status
        self._tasks[task_id]["finished_at"] = time.time()
        return self._tasks[task_id]

    def get(self, task_id: str) -> dict[str, Any] | None:
        return self._tasks.get(task_id)


class WorkflowManager:
    """Chains agents into the workflows shown in the main flowchart."""

    def __init__(self, tasks: TaskManager, state: StateManager) -> None:
        self.tasks = tasks
        self.state = state
        self._workflows: dict[str, Callable[[dict], dict]] = {
            "generate_pipeline": self._wf_generate_pipeline,
            "review_pull_request": self._wf_review_pull_request,
            "pipeline_failed": self._wf_pipeline_failed,
            "pipeline_success": self._wf_pipeline_success,
            "deploy": self._wf_deploy,
            "rollback": self._wf_rollback,
        }

    def events(self) -> list[str]:
        return list(self._workflows)

    def handle(self, event: str, payload: dict[str, Any]) -> dict[str, Any]:
        if event not in self._workflows:
            # single-agent direct call (function calling by name)
            if self.tasks.has(event):
                return self.tasks.call(event, payload)
            return {"error": f"unknown event '{event}'",
                    "known_events": self.events(),
                    "known_agents": [f["name"] for f in self.tasks.functions()]}
        return self._workflows[event](payload)

    # ---------------------------------------------------------- workflows
    def _wf_generate_pipeline(self, p: dict) -> dict:
        tid = self.state.start("generate_pipeline")
        result = self.tasks.call("pipeline_generation", p)
        self.state.record(tid, "pipeline_generation", result)
        if p.get("scan_security") and result.get("workflow_yaml"):
            sec = self.tasks.call("security", {"files": [
                {"path": result["workflow_path"],
                 "content": result["workflow_yaml"]}]})
            self.state.record(tid, "security", sec)
            result["security_scan"] = sec
        status = "completed" if result.get("validation", {}).get("passed") \
            else "completed_with_warnings"
        return self.state.finish(tid, status) | {"output": result}

    def _wf_review_pull_request(self, p: dict) -> dict:
        tid = self.state.start("review_pull_request")
        review = self.tasks.call("code_review", {"mode": "review",
                                                 "diff": p.get("diff", "")})
        self.state.record(tid, "code_review", review)
        if p.get("files"):
            sec = self.tasks.call("security", {"files": p["files"]})
            self.state.record(tid, "security", sec)
            review["security_scan"] = sec
        return self.state.finish(tid, "completed") | {"output": review}

    def _wf_pipeline_failed(self, p: dict) -> dict:
        """FAILED pipeline -> Log Analysis -> Auto-Remediate Workflow YAML with Pipeline Generation Agent."""
        tid = self.state.start("pipeline_failed")
        analysis = self.tasks.call("log_analysis", p)
        self.state.record(tid, "log_analysis", analysis)

        fix = None
        if analysis.get("next_agent") == "code_review" and p.get("file_path"):
            fix = self.tasks.call("code_review", {
                "mode": "fix",
                "file_path": p.get("file_path"),
                "content": p.get("file_content", ""),
                "root_cause": analysis.get("root_cause", ""),
                "suggested_fix": analysis.get("suggested_fix", ""),
            })
            self.state.record(tid, "code_review_fix", fix)

        # Auto-remediate pipeline workflow YAML using error logs and diagnosed root cause
        regenerated = self.tasks.call("pipeline_generation", {
            "files": p.get("files", []),
            "repo_context": p.get("repo_context"),
            "previous_yaml": p.get("workflow_yaml", ""),
            "error_logs": "\n".join(analysis.get("error_excerpt", [])) or p.get("logs", "")[-4000:],
            "root_cause": analysis.get("root_cause", ""),
            "suggested_fix": analysis.get("suggested_fix", ""),
            "scan_security": False,
        })
        self.state.record(tid, "pipeline_generation_remediation", regenerated)

        return self.state.finish(tid, "completed") | {
            "output": {
                "failure_analysis": analysis,
                "proposed_fix": fix,
                "regenerated_yaml": regenerated.get("workflow_yaml") if regenerated else None,
                "regenerated_path": regenerated.get("workflow_path", ".github/workflows/ai-ci-cd.yml") if regenerated else ".github/workflows/ai-ci-cd.yml",
                "template_used": regenerated.get("template_used") if regenerated else None,
                "stack": regenerated.get("stack") if regenerated else None,
                "credits_used": regenerated.get("credits_used", 0) if regenerated else 0,
                "total_tokens": regenerated.get("total_tokens", 0) if regenerated else 0,
            }
        }

    def _wf_pipeline_success(self, p: dict) -> dict:
        return self._wf_deploy(p)

    def _wf_deploy(self, p: dict) -> dict:
        tid = self.state.start("deploy")
        result = self.tasks.call("deployment", {**p, "action": "deploy"})
        self.state.record(tid, "deployment", result)
        return self.state.finish(tid, result.get("status", "unknown")) | {
            "output": result}

    def _wf_rollback(self, p: dict) -> dict:
        tid = self.state.start("rollback")
        result = self.tasks.call("deployment", {**p, "action": "rollback"})
        self.state.record(tid, "rollback", result)
        return self.state.finish(tid, result.get("status", "unknown")) | {
            "output": result}


class Orchestrator:
    """Event Handler facade used by the FastAPI routes."""

    def __init__(self) -> None:
        self.tasks = TaskManager()
        self.state = StateManager()
        self.workflows = WorkflowManager(self.tasks, self.state)

    def handle_event(self, event: str, payload: dict[str, Any]) -> dict:
        return self.workflows.handle(event, payload)

    def task_status(self, task_id: str) -> dict | None:
        return self.state.get(task_id)


orchestrator = Orchestrator()
