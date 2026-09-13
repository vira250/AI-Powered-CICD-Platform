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

KNOWN_FAILURE_TYPES = {
  "DEPENDENCY_ERROR", "COMPILATION_ERROR", "TEST_FAILURE", "LINT_FAILURE",
  "DOCKER_ERROR", "CONFIGURATION_ERROR", "AUTHENTICATION_ERROR", "DEPLOYMENT_ERROR",
  "NETWORK_ERROR", "UNKNOWN_ERROR",
}


SYSTEM_PROMPT = """You are a Senior DevOps Engineer analyzing CI/CD pipeline failure logs.

Your task is to:
1. Identify the root cause(s) of the failure
2. Assess the impact
3. Provide actionable fix suggestions

Output valid JSON only (no markdown, no extra text) with this schema:
{
  "log_summary": "Brief summary of what happened in the pipeline",
  "error_type": "DEPENDENCY_ERROR | COMPILATION_ERROR | TEST_FAILURE | LINT_FAILURE | DOCKER_ERROR | CONFIGURATION_ERROR | AUTHENTICATION_ERROR | DEPLOYMENT_ERROR | NETWORK_ERROR | UNKNOWN_ERROR",
  "severity": "critical | high | medium | low",
  "summary": "Brief summary of what happened in the pipeline",
  "root_cause": "Most likely root cause",
  "evidence": ["Relevant sanitized log line"],
  "failed_job": "Optional job name",
  "failed_step": "Optional step name",
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
  """Parse logs deterministically, then enrich the result with the configured LLM."""
  parsed = parse_logs(log_text)
  failure_type = parsed["failure_type"]
  baseline = deterministic_baseline(failure_type, parsed)

  if not parsed["cleaned_log"].strip():
    return build_result(parsed, status="failed", baseline=baseline, confidence=0.0,
              model={"provider": "rule-based", "name": "parser"})

  if parsed["error_count"] == 0 and parsed["warning_count"] == 0 and failure_type == "UNKNOWN_ERROR":
    return build_result(parsed, status="passed", baseline=baseline, confidence=1.0,
              model={"provider": "rule-based", "name": "parser"})

  error_text = "\n".join(
    f"[Line {entry.line_number}] [{entry.category.upper()}] {entry.message}"
    for entry in parsed["errors"][:30]
  )
  warning_text = "\n".join(parsed["warnings"][:20])
  user_prompt = f"""Analyze this CI/CD pipeline log that has failed.

DETERMINISTIC FAILURE TYPE: {failure_type}
EXTRACTED ERRORS ({parsed['error_count']} total):
{error_text}
WARNINGS ({parsed['warning_count']} total):
{warning_text}
FULL LOG (sanitized and truncated):
{parsed['cleaned_log'][:8000]}
Has Stack Trace: {parsed['has_stack_trace']}
Exit Code: {parsed.get('exit_code', 'unknown')}

Return JSON using the requested schema. Keep the deterministic failure type unless the evidence clearly disproves it.
"""

  try:
    raw_response = call_llm(SYSTEM_PROMPT, user_prompt, expect_json=True)
    result = extract_json_from_response(raw_response)
    if not isinstance(result, dict):
      raise ValueError("LLM response was not a JSON object")
  except (ValueError, TypeError, AttributeError, RuntimeError):
    result = {}

  return build_result(parsed, result=result, baseline=baseline,
            status="failed", model={
              "provider": "google" if result else "rule-based",
              "name": LLM_MODEL if result else "deterministic-fallback",
            })


def deterministic_baseline(failure_type: str, parsed: dict[str, Any]) -> dict[str, Any]:
  labels = {
    "DEPENDENCY_ERROR": ("high", "The pipeline could not resolve or install a required dependency.", "Check the package manifest, lock file, registry, and dependency credentials."),
    "COMPILATION_ERROR": ("high", "The project failed during compilation or build validation.", "Fix the reported source or build configuration error."),
    "TEST_FAILURE": ("high", "One or more automated tests failed in the pipeline.", "Inspect the failing assertion and reproduce it locally."),
    "LINT_FAILURE": ("medium", "The pipeline failed its lint or code-style checks.", "Run the configured formatter and lint command locally."),
    "DOCKER_ERROR": ("high", "The container image build or Docker operation failed.", "Inspect the Dockerfile, build context, base image, and registry access."),
    "CONFIGURATION_ERROR": ("high", "The pipeline is missing or contains invalid configuration.", "Check workflow variables, secrets, environment names, and config syntax."),
    "AUTHENTICATION_ERROR": ("critical", "The pipeline was denied access to a required resource.", "Verify credentials and the required repository or deployment permissions."),
    "DEPLOYMENT_ERROR": ("critical", "The deployment stage failed to release the application.", "Inspect rollout events, target capacity, release configuration, and credentials."),
    "NETWORK_ERROR": ("high", "The pipeline could not reach a required network resource.", "Check service availability, DNS, firewall rules, runner networking, and timeouts."),
    "UNKNOWN_ERROR": ("medium", "The pipeline failed, but the available log evidence does not identify a known failure class.", "Review surrounding log lines and rerun the failed step with debug logging."),
  }
  severity, summary, fix = labels[failure_type]
  evidence = [entry.raw_text or entry.message for entry in parsed["errors"][:5]] or parsed["warnings"][:3]
  if not evidence:
    evidence = [line.strip()[:500] for line in parsed["cleaned_log"].splitlines() if line.strip()][:3]
  return {
    "error_type": failure_type,
    "severity": severity,
    "summary": summary,
    "root_cause": summary,
    "evidence": evidence,
    "failed_job": parsed.get("failed_job"),
    "failed_step": parsed.get("failed_step"),
    "fixes": [SuggestedFix(title="Recommended next step", description=fix, priority="high" if severity in {"high", "critical"} else "medium")],
    "confidence": 0.72 if failure_type != "UNKNOWN_ERROR" else 0.45,
  }


def build_result(parsed: dict[str, Any], baseline: dict[str, Any], *, status: str,
         confidence: float | None = None, result: dict[str, Any] | None = None,
         model: dict[str, str]) -> LogAnalysisResult:
  result = result or {}
  # Deterministic classification has priority over model guesses for known signals.
  failure_type = baseline["error_type"]
  severity = normalize_severity(result.get("severity", baseline["severity"]))
  summary = result.get("summary") or result.get("log_summary") or baseline["summary"]
  root_cause = result.get("root_cause") or baseline["root_cause"]
  model_evidence = result.get("evidence") if isinstance(result.get("evidence"), list) else []
  evidence = list(dict.fromkeys(baseline["evidence"] + model_evidence)) or baseline["evidence"]
  has_specific_llm_fields = any(result.get(field) for field in ("error_type", "root_cause", "evidence", "failed_job", "failed_step"))
  root_causes = [
    RootCause(
      description=str(item.get("description") or baseline["root_cause"]),
      category=str(item.get("category") or "general"),
      confidence=clamp_confidence(item.get("confidence", baseline["confidence"])),
    )
    for item in result.get("root_causes", []) if isinstance(item, dict)
  ] if has_specific_llm_fields else []
  if not root_causes:
    root_causes = [RootCause(description=root_cause, category="general", confidence=baseline["confidence"])]
  fixes = [
    SuggestedFix(
      title=str(item.get("title") or "Recommended next step"),
      description=str(item.get("description") or baseline["fixes"][0].description),
      code_snippet=item.get("code_snippet"),
      priority=str(item.get("priority") or "medium"),
    )
    for item in result.get("suggested_fixes", []) if isinstance(item, dict)
  ] if has_specific_llm_fields else []
  if not fixes:
    fixes = baseline["fixes"]
  score = clamp_confidence(confidence if confidence is not None else result.get("confidence", result.get("confidence_score", baseline["confidence"])))
  return LogAnalysisResult(
    analysis_id=f"log_{uuid.uuid4().hex[:8]}", generated_at=datetime.now(timezone.utc).isoformat(),
    log_summary=summary, status=status, error_type=failure_type, severity=severity,
    summary=summary, root_cause=root_cause, evidence=evidence,
    failed_job=result.get("failed_job") or baseline["failed_job"],
    failed_step=result.get("failed_step") or baseline["failed_step"],
    error_count=parsed["error_count"], warning_count=parsed["warning_count"], errors=parsed["errors"],
    root_causes=root_causes, suggested_fixes=fixes,
    impact=ImpactAnalysis(severity=severity, affected_areas=[failure_type.lower()], description=summary),
    confidence_score=score, confidence=score,
    model=model if has_specific_llm_fields else {"provider": "rule-based", "name": "deterministic-fallback"},
  )


def normalize_severity(value: Any) -> str:
  value = str(value).lower()
  return value if value in {"critical", "high", "medium", "low"} else "medium"

def clamp_confidence(value: Any) -> float:
    """Normalize malformed model confidence values into the public 0..1 contract."""
    try:
        return max(0.0, min(1.0, float(value)))
    except (TypeError, ValueError):
        return 0.5
