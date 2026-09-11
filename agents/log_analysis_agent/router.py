"""Log Analysis Agent — FastAPI router."""

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
import time

from log_analysis_agent.analyzer import analyze_logs

router = APIRouter(prefix="/api/logs", tags=["Log Analysis"])


class LogAnalysisRequest(BaseModel):
    log_text: str


@router.post("/analyze")
async def analyze_pipeline_logs(request: LogAnalysisRequest):
    """Analyze pipeline logs for errors, root causes, and suggested fixes."""
    start = time.time()
    try:
        if not request.log_text.strip():
            raise HTTPException(status_code=400, detail="Log text cannot be empty")
        result = analyze_logs(request.log_text)
        elapsed_ms = int((time.time() - start) * 1000)
        return {
            **result.model_dump(),
            "generation_time_ms": elapsed_ms,
        }
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Log analysis failed: {str(e)}")
