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

# Known failure signatures -> (root cause hint, fix hint, simple?, next_agent_override)
# next_agent_override=None means default routing (code_review for simple fixes)
_SIGNATURES = [
    # ----- YAML / workflow structure errors -> pipeline_generation -----
    (r"(?i)yaml syntax error|invalid workflow file|unexpected value|mapping values are not allowed",
     "Invalid YAML syntax in the GitHub Actions workflow file",
     "Regenerate the workflow YAML with correct syntax.",
     True, "pipeline_generation"),
    (r"(?i)workflow is not valid|expected scalar|could not determine|on is not defined",
     "GitHub Actions workflow structure is invalid",
     "Fix the workflow structure: ensure 'on', 'jobs', and 'steps' are present and correctly formatted.",
     True, "pipeline_generation"),
    (r"(?i)invalid job name|job .* is not defined|unknown key|unrecognized named",
     "Invalid job or step configuration in the workflow YAML",
     "Fix the job/step names and keys in the GitHub Actions workflow.",
     True, "pipeline_generation"),
    (r"(?i)every step must define|each step must have|required property .* is missing",
     "Workflow step missing required fields (uses or run)",
     "Every step in the workflow must have either 'uses' or 'run'. Regenerate the YAML.",
     True, "pipeline_generation"),
    # ----- Dependency / build errors -> code_review -----
    (r"(?i)could not resolve dependencies|package .* does not exist",
     "Missing or incompatible dependency",
     "Add the missing dependency to pom.xml/build.gradle or fix its version.",
     True, None),
    (r"(?i)npm err! code enotfound|npm err! 404",
     "npm registry lookup failed",
     "Check the package name/version in package.json and the runner network.",
     True, None),
    (r"(?i)command not found: (\\S+)",
     "Required tool not installed on the runner",
     "Add a setup step for the missing tool before it is used.",
     True, None),
    (r"(?i)tests? failed|there are test failures|assertionerror",
     "Unit test failure",
     "Inspect the failing test report and fix the assertion or the code under test.",
     False, None),
    (r"(?i)permission denied",
     "Insufficient permissions in workflow step",
     "Add execute permissions (chmod +x) or adjust the job 'permissions:' block.",
     True, None),
    (r"(?i)unauthorized|authentication failed|403",
     "Registry / API authentication failure",
     "Verify the DOCKER_REGISTRY_USER / DOCKER_REGISTRY_TOKEN secrets.",
     True, None),
    (r"(?i)out of memory|heap space",
     "Runner ran out of memory",
     "Raise the tool's heap limits (e.g. MAVEN_OPTS=-Xmx2g) or split the job.",
     False, None),
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
        "YAML_ERROR: <yes|no>  (yes = the failure is caused by invalid GitHub Actions workflow YAML)\n"
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
        for pattern, cause, fix, simple, next_override in _SIGNATURES:
            if re.search(pattern, error_text):
                result = {"root_cause": cause, "suggested_fix": fix,
                          "simple_fix": simple}
                if next_override:
                    result["next_agent_override"] = next_override
                return result
        return None

    def run(self, payload: dict[str, Any]) -> dict[str, Any]:
        logs: str = payload.get("logs", "")
        errors = self._parse(logs)
        error_text = "\n".join(errors)
        signature = self._match_signatures(error_text)

        if self.llm.available():
            error_summary = error_text if error_text.strip() else logs[-6000:]
            user = (
                f"Pipeline/job: {payload.get('job_name', 'unknown')}\n"
                f"Extracted errors / Log context:\n{error_summary[:8000]}\n\n"
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
        is_yaml_error = parsed.get("yaml_error", False)

        # Determine which agent should handle the fix:
        #  - YAML errors -> pipeline_generation (regenerate the workflow)
        #  - Simple code/config errors -> code_review (propose a fix)
        #  - Complex errors -> None (human review needed)
        if signature and signature.get("next_agent_override"):
            next_agent = signature["next_agent_override"]
            is_yaml_error = True
        elif is_yaml_error:
            next_agent = "pipeline_generation"
        elif simple:
            next_agent = "code_review"
        else:
            next_agent = None

        return {
            "agent": self.name,
            "errors_extracted": len(errors),
            "error_excerpt": errors[:20],
            "root_cause": parsed.get("root_cause", ""),
            "impact": parsed.get("impact", ""),
            "suggested_fix": parsed.get("suggested_fix", ""),
            "simple_fix": simple,
            "yaml_error": is_yaml_error,
            "confidence": parsed.get("confidence", 0),
            "next_agent": next_agent,
        }

    @staticmethod
    def _parse_llm_output(raw: str) -> dict[str, Any]:
        def grab(label: str) -> str:
            m = re.search(rf"{label}:\s*(.+?)(?=\n[A-Z ]+:|\Z)", raw,
                          re.DOTALL | re.IGNORECASE)
            return m.group(1).strip() if m else ""

        conf = re.search(r"CONFIDENCE:\s*(\d+)", raw, re.IGNORECASE)
        yaml_err = re.search(r"YAML_ERROR:\s*(yes|no)", raw, re.IGNORECASE)
        return {
            "root_cause": grab("ROOT CAUSE"),
            "impact": grab("IMPACT"),
            "suggested_fix": grab("SUGGESTED FIX"),
            "simple_fix": "yes" in grab("SIMPLE_FIX").lower(),
            "yaml_error": yaml_err is not None and "yes" in yaml_err.group(1).lower(),
            "confidence": int(conf.group(1)) if conf else 50,
        }
