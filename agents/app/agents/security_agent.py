"""Security Agent.

Scans repository file contents for vulnerabilities (secrets, injection
patterns, vulnerable dependency hints) and produces a security report.
Runs as part of the orchestrated pipeline before deployment.
"""
from __future__ import annotations

import re
from typing import Any

from .base import BaseAgent

_PATTERNS = [
    ("SECRET", "critical", re.compile(r"(?i)(aws_secret_access_key|ghp_[A-Za-z0-9]{20,}|-----BEGIN (RSA |EC )?PRIVATE KEY-----)"),
     "Private key or access token committed to the repository"),
    ("CRED", "critical", re.compile(r"(?i)(password|passwd|secret)\s*[:=]\s*['\"][^'\"]{6,}['\"]"),
     "Hard-coded password/secret"),
    ("SQLI", "high", re.compile(r"(?i)(\"|'|\`)\s*\+\s*\w+\s*\+\s*(\"|'|\`).*(SELECT|INSERT|DELETE|UPDATE)"),
     "Possible SQL injection via string concatenation"),
    ("XSS", "high", re.compile(r"dangerouslySetInnerHTML"), "Dangerous React HTML injection"),
    ("CORS", "medium", re.compile(r"Access-Control-Allow-Origin['\"]?\s*[:,]\s*['\"]\*"), "Wildcard CORS origin"),
    ("DEBUG", "low", re.compile(r"(?i)debug\s*=\s*true"), "Debug mode enabled"),
]


class SecurityAgent(BaseAgent):
    name = "security"
    description = ("Scans repository contents for secrets, injection risks "
                   "and insecure configurations; reports findings by severity.")

    def run(self, payload: dict[str, Any]) -> dict[str, Any]:
        # payload: {"files": [{"path": ..., "content": ...}, ...]}
        findings: list[dict[str, Any]] = []
        for f in payload.get("files", []):
            for i, line in enumerate(f.get("content", "").splitlines(), 1):
                for rule, severity, pattern, message in _PATTERNS:
                    if pattern.search(line):
                        findings.append({
                            "file": f.get("path"), "line": i, "rule": rule,
                            "severity": severity, "message": message,
                        })

        verdict = "pass"
        if any(x["severity"] == "critical" for x in findings):
            verdict = "fail"
        elif any(x["severity"] == "high" for x in findings):
            verdict = "warn"

        return {
            "agent": self.name,
            "files_scanned": len(payload.get("files", [])),
            "findings": findings,
            "verdict": verdict,
        }
