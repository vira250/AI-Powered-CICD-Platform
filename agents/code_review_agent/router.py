"""Code Review Agent — FastAPI router."""

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Any, Optional
import time

from code_review_agent.reviewer import review_repository_context, review_pull_request_diff

router = APIRouter(prefix="/api/review", tags=["Code Review"])


class ContextReviewRequest(BaseModel):
    context: dict[str, Any]


class DiffReviewRequest(BaseModel):
    diff: str
    owner: Optional[str] = "unknown"
    repo: Optional[str] = "unknown"
    pr_number: Optional[int] = None
    title: Optional[str] = "Pull Request"
    author: Optional[str] = "unknown"


@router.post("/analyze")
async def analyze_code(request: ContextReviewRequest):
    """Run a full code review on the given RepositoryContext."""
    start = time.time()
    try:
        result = review_repository_context(request.context)
        elapsed_ms = int((time.time() - start) * 1000)
        return {
            **result.model_dump(),
            "generation_time_ms": elapsed_ms,
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Code review failed: {str(e)}")


@router.post("/diff")
@router.post("/pull-request")
async def analyze_diff(request: DiffReviewRequest):
    """Run code review on a GitHub Pull Request unified diff."""
    start = time.time()
    try:
        pr_meta = {
            "owner": request.owner,
            "repo": request.repo,
            "pr_number": request.pr_number,
            "title": request.title,
            "author": request.author,
        }
        result = review_pull_request_diff(request.diff, pr_meta)
        elapsed_ms = int((time.time() - start) * 1000)
        return {
            **result.model_dump(),
            "generation_time_ms": elapsed_ms,
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"PR diff review failed: {str(e)}")
