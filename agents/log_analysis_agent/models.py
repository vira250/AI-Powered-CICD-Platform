"""Pydantic models for the Log Analysis Agent output."""

from pydantic import BaseModel, Field
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
    affected_areas: list[str] = Field(default_factory=list)
    description: str = ""


class LogAnalysisResult(BaseModel):
    analysis_id: str
    generated_at: str
    log_summary: str
    status: str  # "failed", "passed_with_warnings", "passed"
    # Compatibility fields used by the Log Analyzer UI and downstream agents.
    error_type: str = "UNKNOWN_ERROR"
    severity: str = "low"
    summary: str = ""
    root_cause: str = ""
    evidence: list[str] = Field(default_factory=list)
    failed_job: Optional[str] = None
    failed_step: Optional[str] = None
    error_count: int = 0
    warning_count: int = 0
    errors: list[ErrorEntry] = Field(default_factory=list)
    root_causes: list[RootCause] = Field(default_factory=list)
    suggested_fixes: list[SuggestedFix] = Field(default_factory=list)
    impact: ImpactAnalysis = Field(default_factory=lambda: ImpactAnalysis(severity="low", description="No impact"))
    confidence_score: float = 0.0
    confidence: float = 0.0
    model: dict = Field(default_factory=dict)
