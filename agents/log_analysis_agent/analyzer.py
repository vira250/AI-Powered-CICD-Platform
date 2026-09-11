"""
Log Analyzer — uses LLM to perform root cause analysis, impact assessment,
and generate suggested fixes from parsed pipeline log errors.
"""

import uuid
from datetime import datetime, timezone
from typing import Any

from llm_client import call_llm, extract_json_from_response
from log_analysis_agent.models import (
    LogAnalysisResult, ErrorEntry, RootCause, SuggestedFix, ImpactAnalysis
)
from log_analysis_agent.parser import parse_logs
from config import LLM_MODEL


SYSTEM_PROMPT = """You are a Senior DevOps Engineer analyzing CI/CD pipeline failure logs.

Your task is to:
1. Identify the root cause(s) of the failure
2. Assess the impact
3. Provide actionable fix suggestions

Output valid JSON only (no markdown, no extra text) with this schema:
{
  "log_summary": "Brief summary of what happened in the pipeline",
  "status": "failed" | "passed_with_warnings" | "passed",
  "root_causes": [
    {
      "description": "What caused the failure",
      "category": "dependency" | "configuration" | "code" | "infrastructure" | "permission",
      "confidence": 0.95
    }
  ],
  "suggested_fixes": [
    {
      "title": "Short title",
      "description": "Detailed fix description",
      "code_snippet": "optional code example",
      "priority": "high" | "medium" | "low"
    }
  ],
  "impact": {
    "severity": "critical" | "high" | "medium" | "low",
    "affected_areas": ["build", "deployment", "tests"],
    "description": "What is affected by this failure"
  },
  "confidence_score": 0.85
}
"""


def analyze_logs(log_text: str) -> LogAnalysisResult:
    """
    Analyze pipeline logs: parse → LLM analysis → structured result.
    """
    # Step 1: Parse logs for errors/warnings
    parsed = parse_logs(log_text)
    errors = parsed["errors"]
    error_count = parsed["error_count"]
    warning_count = parsed["warning_count"]

    # If no errors found, return a clean result
    if error_count == 0 and warning_count == 0:
        return LogAnalysisResult(
            analysis_id=f"log_{uuid.uuid4().hex[:8]}",
            generated_at=datetime.now(timezone.utc).isoformat(),
            log_summary="Pipeline completed successfully with no errors or warnings.",
            status="passed",
            error_count=0,
            warning_count=0,
            errors=[],
            root_causes=[],
            suggested_fixes=[],
            impact=ImpactAnalysis(severity="low", description="No issues detected"),
            confidence_score=1.0,
            model={"provider": "rule-based", "name": "parser"},
        )

    # Step 2: Build LLM prompt with extracted errors
    error_text = "\n".join([
        f"[Line {e.line_number}] [{e.category.upper()}] {e.message}"
        for e in errors[:30]  # Limit to 30 errors for the prompt
    ])

    warning_text = "\n".join(parsed["warnings"][:20])

    # Truncate log text for the prompt
    truncated_log = log_text[:8000] if len(log_text) > 8000 else log_text

    user_prompt = f"""Analyze this CI/CD pipeline log that has failed.

EXTRACTED ERRORS ({error_count} total):
{error_text}

WARNINGS ({warning_count} total):
{warning_text}

FULL LOG (may be truncated):
{truncated_log}

Has Stack Trace: {parsed['has_stack_trace']}
Exit Code: {parsed.get('exit_code', 'unknown')}

Analyze the root cause, assess impact, and provide fix suggestions. Return JSON as specified.
"""

    # Step 3: Call LLM
    raw_response = call_llm(SYSTEM_PROMPT, user_prompt, expect_json=True)
    result = extract_json_from_response(raw_response)

    # Step 4: Build structured result
    root_causes = [
        RootCause(
            description=rc.get("description", "Unknown"),
            category=rc.get("category", "general"),
            confidence=rc.get("confidence", 0.5),
        )
        for rc in result.get("root_causes", [])
    ]

    suggested_fixes = [
        SuggestedFix(
            title=sf.get("title", "Fix"),
            description=sf.get("description", ""),
            code_snippet=sf.get("code_snippet"),
            priority=sf.get("priority", "medium"),
        )
        for sf in result.get("suggested_fixes", [])
    ]

    impact_data = result.get("impact", {})
    impact = ImpactAnalysis(
        severity=impact_data.get("severity", "medium"),
        affected_areas=impact_data.get("affected_areas", []),
        description=impact_data.get("description", ""),
    )

    return LogAnalysisResult(
        analysis_id=f"log_{uuid.uuid4().hex[:8]}",
        generated_at=datetime.now(timezone.utc).isoformat(),
        log_summary=result.get("log_summary", "Pipeline log analyzed."),
        status=result.get("status", "failed"),
        error_count=error_count,
        warning_count=warning_count,
        errors=errors,
        root_causes=root_causes,
        suggested_fixes=suggested_fixes,
        impact=impact,
        confidence_score=result.get("confidence_score", 0.5),
        model={"provider": "google", "name": LLM_MODEL},
    )
