"""Code Review Agent.

Implements the CodeReview architecture:
  Code Diff (added/modified/deleted lines)
  -> Static Analysis stand-ins (Semgrep/SonarQube-style rule scans:
     syntax, bugs, security, code quality, performance)
  -> LLM Analysis -> Review Report bucketed by severity
  (critical / high / medium / low / suggestions).

It is also invoked by the AI Orchestrator to propose a *fix* for a specific
file when the Log Analysis Agent flags a simple, auto-fixable failure.
"""
from __future__ import annotations

import re
from typing import Any

from .base import BaseAgent

# ---------------------------------------------------------------------------
# Static-analysis rules (lightweight stand-ins for Semgrep / SonarQube).
# Each rule: (id, severity, compiled pattern, message)
# ---------------------------------------------------------------------------
_RULES = [
    ("SEC001", "critical", re.compile(r"(?i)(password|secret|api[_-]?key|token)\s*=\s*['\"][^'\"]{6,}['\"]"),
     "Hard-coded credential detected"),
    ("SEC002", "high", re.compile(r"\beval\s*\("), "Use of eval() is dangerous"),
    ("SEC003", "high", re.compile(r"(?i)exec\s*\(.*request\."), "Possible command injection"),
    ("SEC004", "medium", re.compile(r"verify\s*=\s*False"), "TLS verification disabled"),
    ("BUG001", "high", re.compile(r"==\s*null|!=\s*null"), "Use explicit null checks (equals/Optional in Java)"),
    ("BUG002", "medium", re.compile(r"catch\s*\([^)]*\)\s*\{\s*\}"), "Empty catch block swallows exceptions"),
    ("BUG003", "medium", re.compile(r"console\.log\("), "Debug console.log left in code"),
    ("QLT001", "low", re.compile(r"//\s*TODO|#\s*TODO"), "Unresolved TODO comment"),
    ("PRF001", "medium", re.compile(r"SELECT\s+\*", re.IGNORECASE), "SELECT * may hurt performance"),
    ("PRF002", "low", re.compile(r"for\s*\(.*\)\s*\{[^}]*await", re.DOTALL), "await inside loop — consider batching"),
]


def parse_unified_diff(diff: str) -> dict[str, list[dict[str, Any]]]:
    """Split a unified diff into added / modified / deleted lines per file."""
    files: dict[str, list[dict[str, Any]]] = {}
    current = None
    line_no = 0
    for raw in diff.splitlines():
        if raw.startswith("+++ b/"):
            current = raw[6:]
            files.setdefault(current, [])
        elif raw.startswith("@@"):
            m = re.search(r"\+(\d+)", raw)
            line_no = int(m.group(1)) - 1 if m else 0
        elif current:
            if raw.startswith("+") and not raw.startswith("+++"):
                line_no += 1
                files[current].append({"type": "added", "line": line_no,
                                       "content": raw[1:]})
            elif raw.startswith("-") and not raw.startswith("---"):
                files[current].append({"type": "deleted", "line": line_no,
                                       "content": raw[1:]})
            elif not raw.startswith("\\"):
                line_no += 1
    return files


def static_scan(files: dict[str, list[dict[str, Any]]]) -> list[dict[str, Any]]:
    """Run the rule set over added/modified lines only."""
    findings: list[dict[str, Any]] = []
    for path, changes in files.items():
        for ch in changes:
            if ch["type"] != "added":
                continue
            for rule_id, severity, pattern, message in _RULES:
                if pattern.search(ch["content"]):
                    findings.append({
                        "file": path, "line": ch["line"], "rule": rule_id,
                        "severity": severity, "message": message,
                        "snippet": ch["content"].strip()[:160],
                    })
    return findings


class CodeReviewAgent(BaseAgent):
    name = "code_review"
    description = ("Reviews pull-request diffs using static-analysis rules "
                   "(syntax, bugs, security, quality, performance) plus LLM "
                   "analysis, producing a severity-bucketed review report. "
                   "Can also propose a fix for a failing file.")

    REVIEW_SYSTEM = (
        "You are a senior code reviewer. Review the diff and the static "
        "analysis findings. Respond in EXACTLY this format:\n"
        "CRITICAL: <items or 'none'>\nHIGH: <items or 'none'>\n"
        "MEDIUM: <items or 'none'>\nLOW: <items or 'none'>\n"
        "SUGGESTIONS: <numbered improvement suggestions>"
    )

    FIX_SYSTEM = (
        "You are an automated code-fixing agent. Given a failing pipeline "
        "root cause and the relevant file content, output the COMPLETE fixed "
        "file content only — no fences, no commentary."
    )

    # ------------------------------------------------------------------ run
    def run(self, payload: dict[str, Any]) -> dict[str, Any]:
        mode = payload.get("mode", "review")
        if mode == "fix":
            return self._fix(payload)
        return self._review(payload)

    # ------------------------------------------------------------- review
    def _review(self, payload: dict[str, Any]) -> dict[str, Any]:
        diff = payload.get("diff", "")
        files = parse_unified_diff(diff)
        findings = static_scan(files)

        buckets = {"critical": [], "high": [], "medium": [], "low": [],
                   "suggestions": []}
        for f in findings:
            buckets[f["severity"]].append(
                f"{f['file']}:{f['line']} [{f['rule']}] {f['message']}")

        if self.llm.available() and diff:
            user = (f"Changed files: {list(files)}\n\n"
                    f"Static analysis findings: {findings[:40]}\n\n"
                    f"Diff (truncated):\n{diff[:12000]}")
            llm_buckets = self._parse_review(self.llm.chat(self.REVIEW_SYSTEM, user))
            for k, v in llm_buckets.items():
                buckets.setdefault(k, []).extend(v)

        return {
            "agent": self.name,
            "files_changed": len(files),
            "static_findings": findings,
            "report": buckets,
            "summary": {
                "critical": len(buckets["critical"]),
                "high": len(buckets["high"]),
                "medium": len(buckets["medium"]),
                "low": len(buckets["low"]),
                "suggestions": len(buckets["suggestions"]),
            },
        }

    # ---------------------------------------------------------------- fix
    def _fix(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Propose a fix for a file flagged by the Log Analysis Agent."""
        file_path = payload.get("file_path", "")
        content = payload.get("content", "")
        root_cause = payload.get("root_cause", "")
        suggested_fix = payload.get("suggested_fix", "")

        if self.llm.available() and content:
            user = (f"File: {file_path}\nPipeline failure root cause: "
                    f"{root_cause}\nHint: {suggested_fix}\n\n"
                    f"Current file content:\n{content[:12000]}")
            fixed = re.sub(r"^```[\w-]*\n|\n```$", "",
                           self.llm.chat(self.FIX_SYSTEM, user).strip(),
                           flags=re.MULTILINE).strip()
        else:
            fixed = content  # no-op without LLM

        return {
            "agent": self.name,
            "mode": "fix",
            "file_path": file_path,
            "fixed_content": fixed,
            "changed": fixed != content,
        }

    # ------------------------------------------------------------- helpers
    @staticmethod
    def _parse_review(raw: str) -> dict[str, list[str]]:
        out: dict[str, list[str]] = {}
        for label, key in [("CRITICAL", "critical"), ("HIGH", "high"),
                           ("MEDIUM", "medium"), ("LOW", "low"),
                           ("SUGGESTIONS", "suggestions")]:
            m = re.search(rf"{label}:\s*(.+?)(?=\n[A-Z]+:|\Z)", raw,
                          re.DOTALL)
            if m:
                text = m.group(1).strip()
                if text.lower() != "none":
                    out[key] = [ln.strip("- \t") for ln in text.splitlines()
                                if ln.strip()][:10]
        return out
