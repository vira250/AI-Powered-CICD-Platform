"""FastAPI entrypoint for the AI agents service.

The Spring Boot backend calls these endpoints; it never talks to the LLM
directly.
"""
import logging
from typing import Any, Dict

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from .config import settings
from .llm import llm
from .orchestrator import orchestrator

log = logging.getLogger(__name__)

app = FastAPI(title="AI CI/CD Agents", version="1.0.0")

# Allow the Spring Boot backend (and dev tools) to reach this service
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


class OrchestrateRequest(BaseModel):
    event: str
    payload: Dict[str, Any] = Field(default_factory=dict)


@app.on_event("startup")
def _startup_banner() -> None:
    log.info("=" * 60)
    log.info("  AI CI/CD Agents Service")
    log.info("  LLM model   : %s", settings.llm_model)
    log.info("  LLM base URL: %s", settings.llm_base_url)
    log.info("  LLM key set : %s", "YES" if llm.available() else "NO")
    log.info("  Port         : %s", settings.agents_port)
    log.info("=" * 60)


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "llm_model": settings.llm_model,
            "llm_configured": llm.available()}


@app.get("/functions")
def functions() -> dict:
    """Function-calling catalogue: every agent the orchestrator can invoke."""
    return {"functions": orchestrator.tasks.functions(),
            "workflows": orchestrator.workflows.events()}


@app.get("/agents/status")
def agents_status() -> dict:
    """Shows every registered agent, its role, and the LLM status."""
    agents = orchestrator.tasks.functions()
    return {
        "llm_model": settings.llm_model,
        "llm_configured": llm.available(),
        "agents": [
            {"name": a["name"],
             "description": a["description"],
             "parameters": a["parameters"]}
            for a in agents
        ],
        "workflows": orchestrator.workflows.events(),
    }


from fastapi import FastAPI, HTTPException, Request

@app.post("/orchestrate")
async def orchestrate(request: Request) -> dict:
    import json
    raw = await request.body()
    text = raw.decode("utf-8", errors="ignore")
    log.info("Received raw request body (%d bytes): %s", len(raw), text[:200])
    try:
        body = json.loads(text) if text else {}
    except Exception as e:
        log.error("Failed to parse request JSON: %s", e)
        body = {}
    event = str(body.get("event", ""))
    payload = body.get("payload")
    if not isinstance(payload, dict):
        payload = {}
    log.info("Orchestrating event='%s' with payload keys=%s", event, list(payload.keys()))
    result = orchestrator.handle_event(event, payload)
    if "error" in result and "steps" not in result:
        raise HTTPException(status_code=400, detail=result)
    return result


@app.get("/tasks/{task_id}")
def task_status(task_id: str) -> dict:
    task = orchestrator.task_status(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="task not found")
    return task

