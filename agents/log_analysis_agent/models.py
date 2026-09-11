"""Pydantic models for the Log Analysis Agent output."""

from pydantic import BaseModel
from typing import Optional


class ErrorEntry(BaseModel):
    line_number: Optional[int] = None
    message: str
    category: str = "general"  # build, test, security, deployment, general
    raw_text: str = ""


class RootCause(BaseModel):
    description: str
    category: str  # dependency, configuration, code, infrastructure, permission
    confidence: float = 0.0  # 0.0 to 1.0


class SuggestedFix(BaseModel):
    title: str
    description: str
    code_snippet: Optional[str] = None
    priority: str = "medium"  # high, medium, low


class ImpactAnalysis(BaseModel):
    severity: str  # critical, high, medium, low
    affected_areas: list[str] = []
    description: str = ""


class LogAnalysisResult(BaseModel):
    analysis_id: str
    generated_at: str
    log_summary: str
    status: str  # "failed", "passed_with_warnings", "passed"
    error_count: int = 0
    warning_count: int = 0
    errors: list[ErrorEntry] = []
    root_causes: list[RootCause] = []
    suggested_fixes: list[SuggestedFix] = []
    impact: ImpactAnalysis = ImpactAnalysis(severity="low", description="No impact")
    confidence_score: float = 0.0
    model: dict = {}
