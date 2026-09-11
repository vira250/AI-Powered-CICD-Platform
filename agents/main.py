"""
DeployHub AI Multi-Agent Service — FastAPI application entry point.
Mounts individual agent routers and the central AI Orchestrator workflow engine.
"""

from fastapi import FastAPI, HTTPException, APIRouter
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Any, Optional

from pipeline_agent.router import router as pipeline_router
from code_review_agent.router import router as review_router
from log_analysis_agent.router import router as logs_router
from deployment_agent.router import router as deploy_router
from orchestrator import orchestrator

app = FastAPI(
    title="DeployHub AI Multi-Agent CI/CD Platform",
    description="Multi-agent orchestration: Pipeline Generation, Code Review, Log Analysis, Production Deployment with Health Check & Rollback",
    version="2.0.0",
)

# CORS — allow requests from the Spring Boot backend and React frontend
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:8080", "http://localhost:5173", "*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Mount individual agent routers
app.include_router(pipeline_router)
app.include_router(review_router)
app.include_router(logs_router)
app.include_router(deploy_router)

# Orchestrator Router for multi-agent workflows
orch_router = APIRouter(prefix="/api/orchestrator", tags=["Multi-Agent Orchestrator"])


class WorkflowExecutionRequest(BaseModel):
    workflow: str
    payload: dict[str, Any] = {}


@orch_router.post("/workflow")
async def execute_workflow(request: WorkflowExecutionRequest):
    """Execute a chained multi-agent workflow."""
    try:
        result = orchestrator.execute_workflow(request.workflow, request.payload)
        return result
    except ValueError as ve:
        raise HTTPException(status_code=400, detail=str(ve))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Workflow execution failed: {str(e)}")


@orch_router.get("/workflows")
async def list_workflows():
    """List all available multi-agent workflows."""
    return {
        "workflows": orchestrator.list_workflows(),
        "count": len(orchestrator.list_workflows()),
    }


@orch_router.get("/agents")
async def list_agents():
    """List registered agents, roles, and schemas."""
    return {
        "agents": orchestrator.list_agents(),
        "count": len(orchestrator.list_agents()),
    }


app.include_router(orch_router)


@app.get("/health")
async def health_check():
    """Health check endpoint reflecting all 4 agents and multi-agent engine."""
    return {
        "status": "healthy",
        "service": "deployhub-multi-agents",
        "multi_agent_orchestrator": "active",
        "workflows": orchestrator.list_workflows(),
        "agents": [
            {"name": "Pipeline Generation", "endpoint": "/api/pipeline/generate", "remediate": "/api/pipeline/remediate"},
            {"name": "Code Review", "endpoint": "/api/review/analyze", "diff": "/api/review/diff"},
            {"name": "Log Analysis", "endpoint": "/api/logs/analyze"},
            {"name": "Deployment & Rollback", "endpoint": "/api/deploy/execute", "rollback": "/api/deploy/rollback"},
        ],
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8001, reload=True)
