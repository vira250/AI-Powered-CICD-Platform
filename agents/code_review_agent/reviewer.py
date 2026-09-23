"""Code Reviewer — Synthesizes Static Analysis (Semgrep + SonarQube) with Gemini LLM.

Implements the complete Code Review Agent Architecture:
  PR Diff / Repo Context
    ↓
  Static Analysis Tools (Semgrep, SonarQube, Syntax, Bug, Perf)
    ↓
  LLM Analysis (Gemini)
    ↓
  Review Report (Critical, High, Medium, Low, Suggestions)
    ↓
  Dashboard & GitHub PR Comment
"""

from __future__ import annotations
import json
import uuid
from datetime import datetime, timezone
from typing import Any

from llm_client import call_llm, extract_json_from_response
from code_review_agent.parser import (
    format_directory_tree, format_file_contents, build_truncation_warning
)
from code_review_agent.diff_parser import parse_unified_diff
from code_review_agent.static_analysis import run_static_analysis
from code_review_agent.formatter import format_pr_comment
from code_review_agent.models import (
    ReviewResult, Finding, ReviewStats, Severity, Verdict
)
from config import LLM_MODEL

SYSTEM_PROMPT = """You are a Principal Software Engineer performing an in-depth code review.

You are given:
1. Changed code files or unified pull request diff.
2. Initial findings from static analysis tools (Semgrep security scanner and SonarQube code quality rules).

Your responsibilities:
- Evaluate the static findings: confirm, refine, or filter out false positives.
- Detect deeper issues that static rules cannot catch: subtle logic bugs, race conditions, architecture anti-patterns, edge cases, missing validation.
- Provide concrete, actionable remediation suggestions with code snippets.

Output Format:
Return valid JSON ONLY (no markdown fences, no explanatory text) matching this schema:
{
  "summary": "2-4 sentence executive summary of codebase quality and risks",
  "verdict": "approve" | "request_changes" | "comment",
  "findings": [
    {
      "severity": "critical" | "high" | "medium" | "low" | "info",
      "category": "security" | "code_quality" | "syntax" | "bug" | "performance",
      "source": "llm" | "semgrep" | "sonarqube",
      "file": "path/to/file.ext",
      "line": 42,
      "title": "Concise issue title",
      "description": "Why this is a problem and what could fail",
      "suggestion": "Exact fix recommendation with snippet if helpful",
      "code_snippet": "optional snippet of problematic or fixed code"
    }
  ]
}

Verdict Rules:
- "request_changes": Any critical or high severity findings.
- "comment": Only medium, low, or info findings.
- "approve": No significant issues found.
"""


def review_repository_context(context: dict) -> ReviewResult:
    """Run code review on a full RepositoryContext."""
    repo_info = context.get("repository", {})
    owner = repo_info.get("owner", "unknown")
    repo_name = repo_info.get("repositoryName", "unknown")
    branch = repo_info.get("branch", "main")
    commit_sha = repo_info.get("commitSha", "unknown")

    # 1. Run static analysis on each reviewable file
    static_findings: list[Finding] = []
    files = context.get("files", [])
    for f in files:
        f_path = f.get("path", "")
        f_content = f.get("content", "")
        if f_content and f.get("type") == "text" and not f.get("secret"):
            static_findings.extend(run_static_analysis(f_path, f_content))

    # 2. Build prompt sections
    tree_str = format_directory_tree(context.get("structure", []))
    file_contents_str, files_reviewed, files_skipped = format_file_contents(files)
    truncation_warning = build_truncation_warning(context)

    static_summary = _format_static_findings_for_prompt(static_findings)

    prompt = f"""Review the following repository:
Repository: {owner}/{repo_name} (branch: {branch}, commit: {commit_sha[:8] if commit_sha else 'unknown'})
{truncation_warning}

STATIC ANALYSIS FINDINGS ({len(static_findings)} detected):
{static_summary}

DIRECTORY TREE:
{tree_str}

FILE CONTENTS:
{file_contents_str}
"""

    return _execute_llm_and_build_result(
        prompt=prompt,
        static_findings=static_findings,
        files_reviewed=files_reviewed,
        files_skipped=files_skipped,
        input_meta={"owner": owner, "repo": repo_name, "branch": branch, "commit": commit_sha, "mode": "context"},
    )


def review_pull_request_diff(diff_text: str, pr_meta: dict | None = None) -> ReviewResult:
    """Run code review on a GitHub Pull Request unified diff."""
    pr_meta = pr_meta or {}
    pr_number = pr_meta.get("pr_number")
    repo_name = pr_meta.get("repo", "unknown")
    owner = pr_meta.get("owner", "unknown")

    # 1. Parse diff into structured files (added/modified/deleted)
    diff_files = parse_unified_diff(diff_text)

    # 2. Run static analysis on added and modified content
    static_findings: list[Finding] = []
    for df in diff_files:
        added_text = "\n".join(content for _, content in df.added_lines + df.modified_lines)
        if added_text:
            file_findings = run_static_analysis(df.file, added_text)
            static_findings.extend(file_findings)

    static_summary = _format_static_findings_for_prompt(static_findings)

    # 3. Build prompt focused on changed lines
    diff_snippet = diff_text[:35000] if len(diff_text) > 35000 else diff_text

    prompt = f"""Review the following GitHub Pull Request #{pr_number or 'N/A'}:
Repository: {owner}/{repo_name}
Title: {pr_meta.get('title', 'Pull Request')}
Author: {pr_meta.get('author', 'unknown')}

STATIC ANALYSIS FINDINGS ({len(static_findings)} detected):
{static_summary}

UNIFIED DIFF:
{diff_snippet}
"""

    return _execute_llm_and_build_result(
        prompt=prompt,
        static_findings=static_findings,
        files_reviewed=len(diff_files),
        files_skipped=0,
        input_meta={"owner": owner, "repo": repo_name, "pr_number": pr_number, "mode": "pull_request"},
        pr_number=pr_number,
    )


def _format_static_findings_for_prompt(findings: list[Finding]) -> str:
    if not findings:
        return "None detected by static rules."
    lines = []
    for f in findings[:25]:  # limit to top 25 to preserve token budget
        sev = f.severity.value if isinstance(f.severity, Severity) else str(f.severity)
        lines.append(f"- [{sev.upper()}] ({f.source}) {f.file}:{f.line or 1} - {f.title}: {f.description}")
    if len(findings) > 25:
        lines.append(f"... and {len(findings) - 25} more static findings.")
    return "\n".join(lines)


def _execute_llm_and_build_result(
    prompt: str,
    static_findings: list[Finding],
    files_reviewed: int,
    files_skipped: int,
    input_meta: dict,
    pr_number: int | None = None,
) -> ReviewResult:
    """Call LLM, merge findings, compute stats, and format output."""
    review_id = str(uuid.uuid4())
    generated_at = datetime.now(timezone.utc).isoformat()

    llm_findings: list[Finding] = []
    summary = "Review completed."
    verdict = Verdict.APPROVE.value

    try:
        raw_response = call_llm(
            prompt=prompt,
            system_prompt=SYSTEM_PROMPT,
            temperature=0.2,
            max_output_tokens=4096,
            timeout=45.0,
        )
        parsed = extract_json_from_response(raw_response)
        summary = parsed.get("summary", "Review completed.")
        verdict = parsed.get("verdict", Verdict.APPROVE.value).lower()

        for item in parsed.get("findings", []):
            llm_findings.append(
                Finding(
                    severity=item.get("severity", "medium").lower(),
                    category=item.get("category", "code_quality").lower(),
                    source=item.get("source", "llm"),
                    file=item.get("file", "unknown"),
                    line=item.get("line"),
                    title=item.get("title", "Issue"),
                    description=item.get("description", ""),
                    suggestion=item.get("suggestion", ""),
                    code_snippet=item.get("code_snippet"),
                )
            )
    except Exception as e:
        summary = f"Automated review completed with static analysis. LLM note: {str(e)}"
        # Fallback to static analysis verdict
        if any(f.severity in ("critical", "high") for f in static_findings):
            verdict = Verdict.REQUEST_CHANGES.value
        elif static_findings:
            verdict = Verdict.COMMENT.value
        else:
            verdict = Verdict.APPROVE.value

    # Merge static + LLM findings (deduplicate by file + line + title similarity)
    all_findings = _merge_findings(static_findings, llm_findings)

    # Compute breakdown statistics
    crit = sum(1 for f in all_findings if f.severity.lower() == "critical")
    high = sum(1 for f in all_findings if f.severity.lower() == "high")
    med = sum(1 for f in all_findings if f.severity.lower() == "medium")
    low = sum(1 for f in all_findings if f.severity.lower() in ("low", "info"))
    sugg = sum(1 for f in all_findings if f.suggestion and f.suggestion.strip())

    if crit > 0 or high > 0:
        verdict = Verdict.REQUEST_CHANGES.value
    elif med > 0 or low > 0:
        if verdict != Verdict.REQUEST_CHANGES.value:
            verdict = Verdict.COMMENT.value
    else:
        verdict = Verdict.APPROVE.value

    stats = ReviewStats(
        files_reviewed=files_reviewed,
        files_skipped=files_skipped,
        findings_count=len(all_findings),
        critical_count=crit,
        high_count=high,
        medium_count=med,
        low_count=low,
        suggestions_count=sugg,
        static_findings_count=len(static_findings),
        llm_findings_count=len(llm_findings),
    )

    result = ReviewResult(
        review_id=review_id,
        generated_at=generated_at,
        input=input_meta,
        summary=summary,
        verdict=verdict,
        findings=all_findings,
        stats=stats,
        model={"provider": "google", "name": LLM_MODEL},
    )

    result.pr_comment_markdown = format_pr_comment(result, pr_number=pr_number)
    return result


def _merge_findings(static_findings: list[Finding], llm_findings: list[Finding]) -> list[Finding]:
    """Merge findings, prioritizing LLM descriptions while keeping static rule IDs."""
    merged: list[Finding] = list(llm_findings)
    seen_signatures = {(f.file, f.line, f.category) for f in llm_findings if f.line is not None}

    for sf in static_findings:
        sig: tuple[str, int | None, str] = (sf.file, sf.line, sf.category)
        if sig not in seen_signatures:
            merged.append(sf)
            if sf.line is not None:
                seen_signatures.add((sf.file, sf.line, sf.category))

    return merged
