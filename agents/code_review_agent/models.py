"""Pydantic models for the Code Review Agent output."""

from pydantic import BaseModel, Field
from typing import Optional, Any
from enum import Enum


class Severity(str, Enum):
    CRITICAL = "critical"
    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"
    INFO = "info"


class FindingCategory(str, Enum):
    SECURITY = "security"          # Semgrep-style
    CODE_QUALITY = "code_quality"  # SonarQube-style
    SYNTAX = "syntax"
    BUG = "bug"
    PERFORMANCE = "performance"


class Verdict(str, Enum):
    APPROVE = "approve"
    REQUEST_CHANGES = "request_changes"
    COMMENT = "comment"


class Finding(BaseModel):
    severity: str = "medium"
    category: str = "code_quality"
    source: str = "static+llm"      # semgrep | sonarqube | syntax | bug | performance | llm
    file: str
    line: Optional[int] = None
    title: str
    description: str
    suggestion: str
    code_snippet: Optional[str] = None
    rule_id: Optional[str] = None


class ReviewStats(BaseModel):
    files_reviewed: int = 0
    files_skipped: int = 0
    findings_count: int = 0
    critical_count: int = 0
    high_count: int = 0
    medium_count: int = 0
    low_count: int = 0
    suggestions_count: int = 0
    static_findings_count: int = 0
    llm_findings_count: int = 0
    context_truncated: bool = False


class ReviewResult(BaseModel):
    review_id: str
    generated_at: str
    input: dict = {}
    summary: str
    verdict: str
    findings: list[Finding] = []
    stats: ReviewStats = Field(default_factory=ReviewStats)
    pr_comment_markdown: Optional[str] = None
    model: dict = {}


class DiffFile(BaseModel):
    file: str
    added_lines: list[tuple[int, str]] = []
    modified_lines: list[tuple[int, str]] = []
    deleted_lines: list[tuple[int, str]] = []
    hunks: list[str] = []
    language: str = "unknown"
