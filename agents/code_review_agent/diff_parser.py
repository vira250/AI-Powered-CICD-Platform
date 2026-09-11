"""Unified Diff Parser for Code Review Agent.

Extracts structured per-file additions, modifications, and deletions with line numbers.
"""

from __future__ import annotations
import re
from typing import Any
from .models import DiffFile

EXT_TO_LANG = {
    ".py": "python",
    ".js": "javascript",
    ".jsx": "javascript",
    ".ts": "typescript",
    ".tsx": "typescript",
    ".java": "java",
    ".go": "go",
    ".rs": "rust",
    ".cpp": "cpp",
    ".c": "c",
    ".cs": "csharp",
    ".rb": "ruby",
    ".php": "php",
    ".html": "html",
    ".css": "css",
    ".sql": "sql",
    ".sh": "bash",
    ".yml": "yaml",
    ".yaml": "yaml",
    ".json": "json",
    ".xml": "xml",
}


def detect_language(filename: str) -> str:
    for ext, lang in EXT_TO_LANG.items():
        if filename.endswith(ext):
            return lang
    return "text"


def parse_unified_diff(diff_text: str) -> list[DiffFile]:
    """Parse a unified diff into structured DiffFile objects.

    Distinguishes added lines, modified lines (additions following deletions),
    and deleted lines with exact line numbers.
    """
    if not diff_text or not diff_text.strip():
        return []

    files: list[DiffFile] = []
    current: dict[str, Any] | None = None
    old_line = 0
    new_line = 0
    recent_deletions: list[tuple[int, str]] = []

    for raw_line in diff_text.split("\n"):
        if raw_line.startswith("diff --git"):
            if current:
                files.append(DiffFile(**current))
            match = re.search(r"b/(.+)$", raw_line)
            fname = match.group(1) if match else "unknown"
            current = {
                "file": fname,
                "added_lines": [],
                "modified_lines": [],
                "deleted_lines": [],
                "hunks": [],
                "language": detect_language(fname),
            }
            recent_deletions = []
            continue

        if current is None:
            continue

        hunk_match = re.match(r"^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@", raw_line)
        if hunk_match:
            old_line = int(hunk_match.group(1))
            new_line = int(hunk_match.group(2))
            current["hunks"].append(raw_line)
            recent_deletions = []
            continue

        if raw_line.startswith("---") or raw_line.startswith("+++"):
            continue
        if (raw_line.startswith("index ") or
                raw_line.startswith("new file") or
                raw_line.startswith("deleted file")):
            continue

        if raw_line.startswith("+"):
            content = raw_line[1:]
            if recent_deletions:
                current["modified_lines"].append((new_line, content))
                recent_deletions.pop(0)
            else:
                current["added_lines"].append((new_line, content))
            new_line += 1
        elif raw_line.startswith("-"):
            content = raw_line[1:]
            current["deleted_lines"].append((old_line, content))
            recent_deletions.append((old_line, content))
            old_line += 1
        else:
            old_line += 1
            new_line += 1
            recent_deletions = []

    if current:
        files.append(DiffFile(**current))

    return files
