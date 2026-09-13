"""
Log Parser — extracts errors, warnings, and stack traces from pipeline logs.
Classifies log sections into build, test, security, and deployment categories.
"""

import re
from log_analysis_agent.models import ErrorEntry

MAX_LOG_CHARS = 12000
FAILURE_PATTERNS = [
    ("COMPILATION_ERROR", [
        r"\bTS\d{4}\b",
        r"TypeScript compiler",
    ]),
    ("DEPENDENCY_ERROR", [
        r"npm\s+err!", r"eresolve", r"could not resolve", r"could not be resolved", r"unable to resolve",
        r"module(?:notfound| not found)", r"importerror", r"no matching distribution",
        r"failed to install", r"dependency(?:ies)? .* not found",
    ]),
    ("DOCKER_ERROR", [
        r"docker (?:build|daemon|image|pull)", r"dockerfile", r"container .* failed",
        r"failed to build image", r"manifest unknown",
    ]),
    ("COMPILATION_ERROR", [
        r"compilation (?:error|failed)", r"cannot find symbol", r"compile failed",
        r"ts\d{4}", r"syntaxerror", r"build failed", r"error:.* at .*:\d+:\d+",
    ]),
    ("DEPLOYMENT_ERROR", [
        r"deploy(?:ment)?\s+failed", r"kubectl", r"helm .* failed", r"rollout failed",
        r"release .* failed", r"failed to deploy",
    ]),
    ("TEST_FAILURE", [
        r"tests?\s+(?:failed|failure)", r"assertion(?:error|.*failed)",
        r"(?:^|\s)fail(?:ed)?\s+\S+", r"pytest.*failed", r"junit.*failure",
    ]),
    ("LINT_FAILURE", [
        r"lint(?:ing)?", r"eslint", r"flake8", r"pylint", r"checkstyle",
        r"prettier.*(?:error|failed)", r"style check failed",
    ]),
    ("CONFIGURATION_ERROR", [
        r"configuration (?:error|invalid|missing)", r"missing required (?:env|environment|variable)",
        r"environment variable .* (?:not set|missing)", r"invalid (?:configuration|yaml)",
        r"config(?:uration)? key .* not found",
    ]),
    ("AUTHENTICATION_ERROR", [
        r"permission denied", r"unauthori[sz]ed", r"forbidden", r"\b(?:401|403)\b",
        r"invalid credentials", r"authentication failed", r"access denied",
    ]),
    ("NETWORK_ERROR", [
        r"connection refused", r"network (?:error|unreachable)", r"timed? out", r"timeout",
        r"dns", r"could not resolve host", r"unable to access",
    ]),
]
SECRET_PATTERNS = [
    (r"(?i)(Authorization:\s*Bearer\s+)[^\s]+", r"\1[REDACTED]"),
    (r"(?i)(token|password|passwd|secret|api[_-]?key)(\s*[=:]\s*)[^\s]+", r"\1\2[REDACTED]"),
    (r"gh[pousr]_[A-Za-z0-9_]+", "[REDACTED_GITHUB_TOKEN]"),
]

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
    cleaned_log = redact_secrets((log_text or "")[:MAX_LOG_CHARS])
    lines = cleaned_log.strip().split("\n") if cleaned_log.strip() else []
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
    has_stack_trace = bool(re.search(r"(?i)(at\s+[\w.$]+\(|traceback|stack trace)", cleaned_log))

    # Detect exit code
    exit_code = None
    exit_match = re.search(r"(?i)(?:exit|return|exited with)\s*(?:code|status)?\s*(\d+)", cleaned_log)
    if exit_match:
        exit_code = int(exit_match.group(1))

    return {
        "errors": errors,
        "warnings": warnings,
        "error_count": len(errors),
        "warning_count": len(warnings),
        "has_stack_trace": has_stack_trace,
        "exit_code": exit_code,
        "cleaned_log": cleaned_log,
        "failure_type": classify_failure(cleaned_log),
        "failed_job": extract_named_value(cleaned_log, r"(?im)^job(?: name)?\s*[:=]\s*(.+)$"),
        "failed_step": extract_step(cleaned_log),
    }


def redact_secrets(log_text: str) -> str:
    """Remove common credentials before logs are sent to the language model."""
    redacted = log_text
    for pattern, replacement in SECRET_PATTERNS:
        redacted = re.sub(pattern, replacement, redacted)
    return redacted


def classify_failure(log_text: str) -> str:
    """Return the first high-signal CI/CD failure class found in sanitized logs."""
    for failure_type, patterns in FAILURE_PATTERNS:
        if any(re.search(pattern, log_text, re.IGNORECASE) for pattern in patterns):
            return failure_type
    return "UNKNOWN_ERROR"


def extract_named_value(log_text: str, pattern: str) -> str | None:
    match = re.search(pattern, log_text)
    return match.group(1).strip()[:200] if match else None


def extract_step(log_text: str) -> str | None:
    for pattern in (r"(?im)^##\[group\]Run\s+(.+)$", r"(?im)^step\s*[:=]\s*(.+)$"):
        value = extract_named_value(log_text, pattern)
        if value:
            return value
    return None
