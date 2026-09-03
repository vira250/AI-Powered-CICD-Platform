"""AI Orchestrator.

Implements the Orchestrator architecture:
  Incoming Event -> Event Handler -> Workflow Manager -> Task Manager
  -> dispatch to one of the registered agents (function calling)
  -> Result Aggregator -> State Manager (in-memory + handed back to the
     Spring Boot backend for persistence in PostgreSQL).

The Task Manager exposes each agent as a callable "function" (name,
description, input schema) — mirroring LLM function-calling.
"""
from __future__ import annotations

import time
import uuid
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeout
from typing import Any, Callable

from .agents import PipelineGenerationAgent

TASK_TIMEOUT_SECONDS = 300
MAX_RETRIES = 2


class TaskManager:
    """Function-calling registry: maps a function name to an agent call."""

    def __init__(self) -> None:
        self._agents = {
            "pipeline_generation": PipelineGenerationAgent(),
        }

    # -- function-calling style schema, surfaced at /functions -------------
    def functions(self) -> list[dict[str, Any]]:
        schemas = {
            "pipeline_generation": {"files": "list[str] — repo file paths"},
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
    """Manages the pipeline generation workflow."""

    def __init__(self, tasks: TaskManager, state: StateManager) -> None:
        self.tasks = tasks
        self.state = state
        self._workflows: dict[str, Callable[[dict], dict]] = {
            "generate_pipeline": self._wf_generate_pipeline,
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
        status = "completed" if result.get("validation", {}).get("passed") \
            else "completed_with_warnings"
        return self.state.finish(tid, status) | {"output": result}


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
