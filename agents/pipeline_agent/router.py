"""
Pipeline Generation Agent — FastAPI router.
Orchestrates the complete architecture:
  1. Repository Analyzer
  2. Technology Detection
  3. Pipeline Planner
  4. AI Pipeline Generator
  5. Pipeline Validator (YAML syntax, Security, Best Practices)
  6. Feedback Loop (Regenerate / Fix YAML) -> Final Workflow (.github/workflows/ci.yml)
"""

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Any, Optional, List
import time

from pipeline_agent.analyzer import analyze_repository
from pipeline_agent.planner import plan_pipeline
from pipeline_agent.generator import remediate_pipeline_yaml
from pipeline_agent.validator import validate_pipeline
from pipeline_agent.engine import pipeline_engine

router = APIRouter(prefix="/api/pipeline", tags=["Pipeline Generation"])


class PipelineRequest(BaseModel):
    """Request body — expects the full RepositoryContext JSON from the backend."""
    context: dict[str, Any]


class PipelinePlanRequest(BaseModel):
    """Request body for planning a pipeline."""
    tech: Optional[dict[str, Any]] = None
    context: Optional[dict[str, Any]] = None


class PipelineValidationRequest(BaseModel):
    """Request body for validating workflow YAML."""
    yaml_content: str


class PipelineRemediationRequest(BaseModel):
    """Request body for self-healing pipeline remediation."""
    failed_yaml: str
    error_logs: str
    root_cause: Optional[str] = ""
    suggested_fix: Optional[str] = ""


class PipelineResponse(BaseModel):
    """Response containing the generated pipeline YAML, validation, and architecture trace."""
    yaml_content: str
    file_path: str = ".github/workflows/ci.yml"
    technology: Optional[dict[str, Any]] = None
    plan_summary: Optional[dict[str, Any]] = None
    validation: dict[str, Any]
    validation_passed: bool = True
    iterations_count: int = 1
    iterations_history: Optional[List[dict[str, Any]]] = None
    architecture_trace: Optional[dict[str, Any]] = None
    generation_time_ms: int
    status: str  # "success" | "warning" | "error"
    message: str


@router.post("/generate", response_model=PipelineResponse)
async def generate_pipeline(request: PipelineRequest):
    """
    Generate a complete CI/CD pipeline YAML strictly following the architecture:
      Repository Analyzer -> Technology Detection -> Pipeline Planner ->
      AI Pipeline Generator -> Pipeline Validator -> (Auto-Fix Feedback Loop if failed) ->
      Final Workflow (.github/workflows/ci.yml)
    """
    try:
        result = pipeline_engine.run(request.context)

        plan = result.get("plan", {})
        stages = plan.get("stages", [])

        return PipelineResponse(
            yaml_content=result["yaml_content"],
            file_path=result.get("workflow_file", ".github/workflows/ci.yml"),
            technology=result.get("technology"),
            plan_summary={
                "language": plan.get("language", "Unknown"),
                "framework": plan.get("framework", "None"),
                "build_tool": plan.get("build_tool", "Unknown"),
                "test_framework": plan.get("test_framework", "None"),
                "stage_count": len(stages),
                "stages": [s.get("name", "") for s in stages],
                "execution_strategy": plan.get("execution_strategy"),
            },
            validation=result["validation"],
            validation_passed=result["validation_passed"],
            iterations_count=result["iterations_count"],
            iterations_history=result["iterations_history"],
            architecture_trace=result.get("architecture_trace"),
            generation_time_ms=result["generation_time_ms"],
            status=result["status"],
            message=result["message"],
        )

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Pipeline generation failed: {str(e)}")


@router.post("/analyze")
async def analyze_repo(request: PipelineRequest):
    """
    Stage 1 & 2: Repository Analyzer & Technology Detection.
    """
    try:
        analysis = analyze_repository(request.context)
        return analysis
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Repository analysis failed: {str(e)}")


@router.post("/plan")
async def plan_pipeline_endpoint(request: PipelinePlanRequest):
    """
    Stage 3: Pipeline Planner (CI/CD Stages + Execution Strategy).
    """
    try:
        tech = request.tech
        if not tech and request.context:
            analysis = analyze_repository(request.context)
            tech = analysis["tech"]
        elif not tech:
            raise ValueError("Must provide either 'tech' or 'context'")

        plan = plan_pipeline(tech)
        return plan
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Pipeline planning failed: {str(e)}")


@router.post("/validate")
async def validate_pipeline_endpoint(request: PipelineValidationRequest):
    """
    Stage 5: Pipeline Validator (YAML Syntax + Security Validation + Best Practice Validation).
    """
    try:
        result = validate_pipeline(request.yaml_content)
        return result
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Pipeline validation failed: {str(e)}")


@router.post("/remediate", response_model=PipelineResponse)
async def remediate_pipeline(request: PipelineRemediationRequest):
    """
    Cross-Agent Self-Healing Loop:
    Regenerates a fixed GitHub Actions workflow YAML based on failure logs.
    """
    start = time.time()

    try:
        healed_yaml = remediate_pipeline_yaml(
            failed_yaml=request.failed_yaml,
            error_logs=request.error_logs,
            root_cause=request.root_cause or "",
            suggested_fix=request.suggested_fix or "",
        )

        validation = validate_pipeline(healed_yaml)
        elapsed_ms = int((time.time() - start) * 1000)

        status = "success" if validation["valid"] else ("warning" if validation["warnings"] else "error")
        message = "Pipeline self-healed successfully" if validation["valid"] else "Pipeline remediated with warnings"

        return PipelineResponse(
            yaml_content=healed_yaml,
            file_path=".github/workflows/ci.yml",
            technology=None,
            plan_summary=None,
            validation=validation,
            validation_passed=validation["valid"],
            iterations_count=1,
            generation_time_ms=elapsed_ms,
            status=status,
            message=message,
        )

    except Exception as e:
        elapsed_ms = int((time.time() - start) * 1000)
        raise HTTPException(status_code=500, detail=f"Pipeline remediation failed: {str(e)}")
