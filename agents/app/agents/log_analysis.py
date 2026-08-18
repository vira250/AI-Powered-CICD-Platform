"""Log Analysis Agent.

Implements the LogAnalysis architecture:
  Pipeline Logs -> Log Parser -> Error Extraction -> Context Analysis
  -> Root Cause Analysis -> LLM -> AI Failure Analysis
  (root cause, impact analysis, suggested fix, confidence score)
"""
from __future__ import annotations

import re
from typing import Any

from .base import BaseAgent

_ERROR_PATTERNS = [
    re.compile(r"(?i)\b(error|exception|failed|failure|fatal)\b[:\s].*"),
    re.compile(r"(?i)npm err!.*"),
    re.compile(r"(?i)\[error\].*"),
    re.compile(r"(?i)process completed with exit code [1-9]\d*.*"),
    re.compile(r"(?i)build failed.*"),
]

# Known failure signatures -> (root cause hint, fix hint, simple?)
_SIGNATURES = [
    (r"(?i)could not resolve dependencies|package .* does not exist",
     "Missing or incompatible dependency",
     "Add the missing dependency to pom.xml/build.gradle or fix its version.",
     True),
    (r"(?i)npm err! code enotfound|npm err! 404",
     "npm registry lookup failed",
     "Check the package name/version in package.json and the runner network.",
     True),
    (r"(?i)command not found: (\S+)",
     "Required tool not installed on the runner",
     "Add a setup step for the missing tool before it is used.",
     True),
    (r"(?i)tests? failed|there are test failures|assertionerror",
     "Unit test failure",
     "Inspect the failing test report and fix the assertion or the code under test.",
     False),
    (r"(?i)permission denied",
     "Insufficient permissions in workflow step",
     "Add execute permissions (chmod +x) or adjust the job 'permissions:' block.",
     True),
    (r"(?i)unauthorized|authentication failed|403",
     "Registry / API authentication failure",
     "Verify the DOCKER_REGISTRY_USER / DOCKER_REGISTRY_TOKEN secrets.",
     True),
    (r"(?i)out of memory|heap space",
     "Runner ran out of memory",
     "Raise the tool's heap limits (e.g. MAVEN_OPTS=-Xmx2g) or split the job.",
     False),
]


class LogAnalysisAgent(BaseAgent):
    name = "log_analysis"
    description = ("Parses CI/CD logs, extracts errors, performs context and "
                   "root-cause analysis, then uses the LLM to produce an AI "
                   "failure analysis with a suggested fix and confidence.")

    SYSTEM = (
        "You are a senior DevOps engineer analysing a failed CI/CD pipeline. "
        "Given extracted error lines, respond in EXACTLY this format:\n"
        "ROOT CAUSE: <one paragraph>\n"
        "IMPACT: <one paragraph>\n"
        "SUGGESTED FIX: <numbered steps>\n"
        "SIMPLE_FIX: <yes|no>  (yes = a small code/config change can fix it)\n"
        "CONFIDENCE: <0-100>"
    )

    # -- Log Parser + Error Extraction --------------------------------------
    @staticmethod
    def _parse(logs: str) -> list[str]:
        lines = logs.splitlines()
        errors: list[str] = []
        for i, line in enumerate(lines):
            if any(p.match(line) for p in _ERROR_PATTERNS):
                # capture the error plus 2 lines of trailing context
                errors.extend(lines[i:i + 3])
        return errors[:120]

    # -- Context / Root Cause (rule layer) -----------------------------------
    @staticmethod
    def _match_signatures(error_text: str) -> dict[str, Any] | None:
        for pattern, cause, fix, simple in _SIGNATURES:
            if re.search(pattern, error_text):
                return {"root_cause": cause, "suggested_fix": fix,
                        "simple_fix": simple}
        return None

    def run(self, payload: dict[str, Any]) -> dict[str, Any]:
        logs: str = payload.get("logs", "")
        errors = self._parse(logs)
        error_text = "\n".join(errors)
        signature = self._match_signatures(error_text)

        if self.llm.available() and errors:
            user = (
                f"Pipeline/job: {payload.get('job_name', 'unknown')}\n"
                f"Extracted errors (with context):\n{error_text[:8000]}\n\n"
                f"Rule-based hint: {signature or 'none'}"
            )
            raw = self.llm.chat(self.SYSTEM, user)
            parsed = self._parse_llm_output(raw)
        else:
            # Offline fallback keeps the flow working without an LLM key.
            parsed = {
                "root_cause": (signature or {}).get(
                    "root_cause",
                    "See extracted errors — LLM key not configured for deeper analysis."),
                "impact": "Pipeline stage failed; downstream jobs were skipped.",
                "suggested_fix": (signature or {}).get(
                    "suggested_fix", "Review the extracted error lines above."),
                "simple_fix": (signature or {}).get("simple_fix", False),
                "confidence": 70 if signature else 40,
            }

        simple = parsed.get("simple_fix", False)
        return {
            "agent": self.name,
            "errors_extracted": len(errors),
            "error_excerpt": errors[:20],
            "root_cause": parsed.get("root_cause", ""),
            "impact": parsed.get("impact", ""),
            "suggested_fix": parsed.get("suggested_fix", ""),
            "simple_fix": simple,
            "confidence": parsed.get("confidence", 0),
            # Signal for the AI Orchestrator: escalate simple fixes to the
            # Code Review Agent (see main flowchart).
            "next_agent": "code_review" if simple else None,
        }

    @staticmethod
    def _parse_llm_output(raw: str) -> dict[str, Any]:
        def grab(label: str) -> str:
            m = re.search(rf"{label}:\s*(.+?)(?=\n[A-Z ]+:|\Z)", raw,
                          re.DOTALL | re.IGNORECASE)
            return m.group(1).strip() if m else ""

        conf = re.search(r"CONFIDENCE:\s*(\d+)", raw, re.IGNORECASE)
        return {
            "root_cause": grab("ROOT CAUSE"),
            "impact": grab("IMPACT"),
            "suggested_fix": grab("SUGGESTED FIX"),
            "simple_fix": "yes" in grab("SIMPLE_FIX").lower(),
            "confidence": int(conf.group(1)) if conf else 50,
        }
