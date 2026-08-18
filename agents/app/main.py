"""FastAPI entrypoint for the AI agents service.

The Spring Boot backend calls these endpoints; it never talks to the LLM
directly.
"""
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from .config import settings
from .llm import llm
from .orchestrator import orchestrator

app = FastAPI(title="AI CI/CD Agents", version="1.0.0")


class OrchestrateRequest(BaseModel):
    event: str                 # workflow event or direct agent (function) name
    payload: dict = {}


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "llm_model": settings.llm_model,
            "llm_configured": llm.available()}


@app.get("/functions")
def functions() -> dict:
    """Function-calling catalogue: every agent the orchestrator can invoke."""
    return {"functions": orchestrator.tasks.functions(),
            "workflows": orchestrator.workflows.events()}


@app.post("/orchestrate")
def orchestrate(req: OrchestrateRequest) -> dict:
    result = orchestrator.handle_event(req.event, req.payload)
    if "error" in result and "steps" not in result:
        raise HTTPException(status_code=400, detail=result)
    return result


@app.get("/tasks/{task_id}")
def task_status(task_id: str) -> dict:
    task = orchestrator.task_status(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="task not found")
    return task
