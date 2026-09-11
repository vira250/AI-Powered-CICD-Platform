"""Deployment Agent — FastAPI router."""

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel
from typing import Any, Optional
import time

from deployment_agent.planner import plan_deployment
from deployment_agent.executor import execute_deployment, manual_rollback, get_version_history

router = APIRouter(prefix="/api/deploy", tags=["Deployment Planning & Execution"])


class DeployPlanRequest(BaseModel):
    context: dict[str, Any]


class DeployExecuteRequest(BaseModel):
    owner: str
    repo: str
    commit_sha: str
    environment: Optional[str] = "production"
    image_tag: Optional[str] = None
    health_endpoint: Optional[str] = "/health"
    simulate_health_failure: Optional[bool] = False


class RollbackRequest(BaseModel):
    owner: str
    repo: str
    target_version: Optional[str] = None


@router.post("/plan")
async def generate_plan(request: DeployPlanRequest):
    """Generate a deployment plan (Dockerfile, docker-compose, strategy) from RepositoryContext."""
    start = time.time()
    try:
        plan = plan_deployment(request.context)
        elapsed_ms = int((time.time() - start) * 1000)
        return {
            **plan.model_dump(),
            "generation_time_ms": elapsed_ms,
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Deployment planning failed: {str(e)}")


@router.post("/execute")
async def run_deployment(request: DeployExecuteRequest):
    """
    Execute deployment lifecycle matching flowchart:
    Build/use Docker image -> Deploy to Production -> Health Check
    -> If healthy: update Version History (current + previous)
    -> If unhealthy: trigger automatic Rollback to previous version.
    """
    try:
        result = execute_deployment(
            owner=request.owner,
            repo=request.repo,
            commit_sha=request.commit_sha,
            environment=request.environment or "production",
            image_tag=request.image_tag,
            health_endpoint=request.health_endpoint or "/health",
            simulate_health_failure=request.simulate_health_failure or False,
        )
        return result.model_dump()
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Deployment execution failed: {str(e)}")


@router.post("/rollback")
async def rollback(request: RollbackRequest):
    """Manually rollback production deployment to a specified or previous version."""
    try:
        result = manual_rollback(
            owner=request.owner,
            repo=request.repo,
            target_version=request.target_version,
        )
        return result.model_dump()
    except ValueError as ve:
        raise HTTPException(status_code=400, detail=str(ve))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Rollback failed: {str(e)}")


@router.get("/history")
async def view_history(owner: str = Query(...), repo: str = Query(...)):
    """Retrieve Version History (Current Version and Previous Versions) for a repository."""
    try:
        history = get_version_history(owner, repo)
        return history.model_dump()
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch version history: {str(e)}")
