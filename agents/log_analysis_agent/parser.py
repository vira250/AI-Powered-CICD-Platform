"""
Log Parser — extracts errors, warnings, and stack traces from pipeline logs.
Classifies log sections into build, test, security, and deployment categories.
"""

import re
from log_analysis_agent.models import ErrorEntry

# Patterns that indicate errors in CI/CD logs
ERROR_PATTERNS = [
    (r"(?i)^error[:\s](.+)", "general"),
    (r"(?i)^fatal[:\s](.+)", "general"),
    (r"(?i)\bfailed\b.*", "general"),
    (r"(?i)exception[:\s](.+)", "general"),
    (r"(?i)build failure", "build"),
    (r"(?i)compilation error", "build"),
    (r"(?i)cannot find symbol", "build"),
    (r"(?i)npm ERR!", "build"),
    (r"(?i)ModuleNotFoundError", "build"),
    (r"(?i)ImportError", "build"),
    (r"(?i)SyntaxError", "build"),
    (r"(?i)test.*fail", "test"),
    (r"(?i)assertion.*fail", "test"),
    (r"(?i)FAIL\s+\S+", "test"),
    (r"(?i)tests?\s+failed", "test"),
    (r"(?i)vulnerability found", "security"),
    (r"(?i)CVE-\d{4}-\d+", "security"),
    (r"(?i)security.*issue", "security"),
    (r"(?i)deploy.*fail", "deployment"),
    (r"(?i)connection refused", "deployment"),
    (r"(?i)timeout", "deployment"),
    (r"(?i)permission denied", "deployment"),
    (r"(?i)exit code [1-9]\d*", "general"),
    (r"(?i)process exited with code [1-9]", "general"),
]

WARNING_PATTERNS = [
    (r"(?i)^warning[:\s](.+)", "general"),
    (r"(?i)^warn[:\s](.+)", "general"),
    (r"(?i)deprecated", "general"),
]


def parse_logs(log_text: str) -> dict:
    """
    Parse pipeline log text and extract structured information.

    Returns:
        {
            "errors": list[ErrorEntry],
            "warnings": list[str],
            "error_count": int,
            "warning_count": int,
            "log_sections": dict of categorized text,
            "has_stack_trace": bool,
            "exit_code": int | None,
        }
    """
    lines = log_text.strip().split("\n")
    errors: list[ErrorEntry] = []
    warnings: list[str] = []
    seen_errors: set[str] = set()

    for i, line in enumerate(lines, 1):
        stripped = line.strip()
        if not stripped:
            continue

        # Check error patterns
        for pattern, category in ERROR_PATTERNS:
            if re.search(pattern, stripped):
                key = stripped[:100]
                if key not in seen_errors:
                    seen_errors.add(key)
                    errors.append(ErrorEntry(
                        line_number=i,
                        message=stripped[:500],
                        category=category,
                        raw_text=stripped[:1000],
                    ))
                break

        # Check warning patterns
        for pattern, _ in WARNING_PATTERNS:
            if re.search(pattern, stripped):
                warnings.append(stripped[:500])
                break

    # Detect stack traces
    has_stack_trace = bool(re.search(r"(?i)(at\s+[\w.$]+\(|traceback|stack trace)", log_text))

    # Detect exit code
    exit_code = None
    exit_match = re.search(r"(?i)(?:exit|return|exited with)\s*(?:code|status)?\s*(\d+)", log_text)
    if exit_match:
        exit_code = int(exit_match.group(1))

    return {
        "errors": errors,
        "warnings": warnings,
        "error_count": len(errors),
        "warning_count": len(warnings),
        "has_stack_trace": has_stack_trace,
        "exit_code": exit_code,
    }
