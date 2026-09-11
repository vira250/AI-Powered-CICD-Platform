"""Static Analysis Engine for Code Review Agent.

Implements the multi-faceted analysis components from the Code Review Architecture:
  1. Semgrep-style Security Analysis (OWASP Top 10, Secrets, Injections, Deserialization)
  2. SonarQube-style Code Quality (Smells, Complexity, Unchecked Nulls, Dead Code)
  3. Syntax Analysis (AST / parsing checks where feasible)
  4. Bug Detection (Resource leaks, boundary errors, logic bugs)
  5. Performance Analysis (N+1 queries, async blocking, unindexed scans)
"""

from __future__ import annotations
import ast
import re
from typing import Any
from .models import Finding, FindingCategory, Severity

# ── 1. SEMGREP-STYLE SECURITY RULES ──────────────────────────────────────
SEMGREP_RULES = [
    {
        "id": "SEC-001",
        "name": "SQL Injection Risk",
        "severity": Severity.CRITICAL,
        "category": FindingCategory.SECURITY,
        "source": "semgrep",
        "pattern": re.compile(
            r"""(?i)(?:execute|executeQuery|executeUpdate|raw|query)\s*\(\s*(?:f['"]|['"].*?%s|['"].*?\+\s*\w+|\$query|\.format\()""",
            re.MULTILINE,
        ),
        "title": "Potential SQL Injection Vulnerability",
        "description": "Dynamic SQL construction using string concatenation, f-strings, or unescaped formatters detected.",
        "suggestion": "Use parameterized queries, prepared statements, or ORM parameter binding.",
    },
    {
        "id": "SEC-002",
        "name": "Hardcoded Secret / API Key",
        "severity": Severity.CRITICAL,
        "category": FindingCategory.SECURITY,
        "source": "semgrep",
        "pattern": re.compile(
            r"""(?i)(?:password|secret|api_?key|access_?token|auth_?token|private_?key)\s*[:=]\s*['"][A-Za-z0-9_\-\.\/]{8,}['"]""",
            re.MULTILINE,
        ),
        "title": "Hardcoded Credential / Secret Detected",
        "description": "Sensitive credential appears to be hardcoded directly into the source code.",
        "suggestion": "Move credentials to environment variables or secret manager (Vault, AWS Secrets Manager, GitHub Secrets).",
    },
    {
        "id": "SEC-003",
        "name": "Command Injection / Code Execution",
        "severity": Severity.CRITICAL,
        "category": FindingCategory.SECURITY,
        "source": "semgrep",
        "pattern": re.compile(
            r"""(?i)(?:eval\s*\(|exec\s*\(|os\.system\s*\(|subprocess\.Popen\s*\(.*shell\s*=\s*True|Runtime\.getRuntime\(\)\.exec\()""",
            re.MULTILINE,
        ),
        "title": "Arbitrary Code / Command Execution Risk",
        "description": "Execution of dynamic shell command or untrusted code string detected.",
        "suggestion": "Avoid shell=True or eval. Use safe APIs with argument lists.",
    },
    {
        "id": "SEC-004",
        "name": "Insecure Deserialization",
        "severity": Severity.HIGH,
        "category": FindingCategory.SECURITY,
        "source": "semgrep",
        "pattern": re.compile(
            r"""(?i)(?:pickle\.loads?|yaml\.load\s*\([^,)]+\)|ObjectInputStream\.readObject)""",
            re.MULTILINE,
        ),
        "title": "Insecure Deserialization",
        "description": "Deserializing untrusted data can lead to remote code execution.",
        "suggestion": "Use safe loaders like yaml.safe_load() or JSON serializers.",
    },
    {
        "id": "SEC-005",
        "name": "CORS Wildcard with Credentials",
        "severity": Severity.HIGH,
        "category": FindingCategory.SECURITY,
        "source": "semgrep",
        "pattern": re.compile(
            r"""(?i)(?:Access-Control-Allow-Origin['"]?\s*[:=]\s*['"]\*['"]|allow_origins\s*=\s*\[\s*['"]\*['"]\s*\])""",
            re.MULTILINE,
        ),
        "title": "Overly Permissive CORS Policy",
        "description": "Wildcard CORS origin (*) allows any domain to query the backend.",
        "suggestion": "Explicitly whitelist trusted frontend origins instead of '*'.",
    },
    {
        "id": "SEC-006",
        "name": "Weak Cryptographic Hash",
        "severity": Severity.MEDIUM,
        "category": FindingCategory.SECURITY,
        "source": "semgrep",
        "pattern": re.compile(
            r"""(?i)(?:hashlib\.md5|hashlib\.sha1|MessageDigest\.getInstance\s*\(\s*['"]MD5['"]|MessageDigest\.getInstance\s*\(\s*['"]SHA-1['"])""",
            re.MULTILINE,
        ),
        "title": "Weak Cryptographic Hash Function",
        "description": "MD5 and SHA-1 have known collision vulnerabilities.",
        "suggestion": "Use SHA-256, SHA-512, or bcrypt/argon2 for passwords.",
    },
]

# ── 2. SONARQUBE-STYLE CODE QUALITY RULES ────────────────────────────────
SONARQUBE_RULES = [
    {
        "id": "QUAL-001",
        "name": "Empty Catch / Except Block",
        "severity": Severity.HIGH,
        "category": FindingCategory.CODE_QUALITY,
        "source": "sonarqube",
        "pattern": re.compile(
            r"""(?i)(?:catch\s*\([^)]+\)\s*\{\s*\}|except(?:\s+\w+)?:(?:\s*\n\s*pass))""",
            re.MULTILINE,
        ),
        "title": "Swallowed Exception / Empty Catch Block",
        "description": "Empty exception handler completely hides errors and failures.",
        "suggestion": "Log the error, rethrow, or handle the exception explicitly.",
    },
    {
        "id": "QUAL-002",
        "name": "Print / Console Log in Production",
        "severity": Severity.LOW,
        "category": FindingCategory.CODE_QUALITY,
        "source": "sonarqube",
        "pattern": re.compile(
            r"""(?:console\.log\(|System\.out\.println\(|print\s*\()""",
            re.MULTILINE,
        ),
        "title": "Standard Output Logging Detected",
        "description": "Standard output print statements should not be used for production logging.",
        "suggestion": "Use a structured logging framework (SLF4J/Logback, Python logging, or Winston).",
    },
    {
        "id": "QUAL-003",
        "name": "TODO / FIXME Left in Code",
        "severity": Severity.INFO,
        "category": FindingCategory.CODE_QUALITY,
        "source": "sonarqube",
        "pattern": re.compile(
            r"""(?i)(?://|#|/\*)\s*(?:TODO|FIXME|HACK|XXX)\b.*$""",
            re.MULTILINE,
        ),
        "title": "Unresolved TODO / FIXME Marker",
        "description": "Unfinished code work marker committed to the repository.",
        "suggestion": "Resolve before merging or track in issue tracker.",
    },
    {
        "id": "QUAL-004",
        "name": "Catch Generic Exception / Throwable",
        "severity": Severity.MEDIUM,
        "category": FindingCategory.CODE_QUALITY,
        "source": "sonarqube",
        "pattern": re.compile(
            r"""(?i)(?:catch\s*\(\s*(?:Exception|Throwable)\b|except\s*Exception:)""",
            re.MULTILINE,
        ),
        "title": "Catching Overly Broad Exception Type",
        "description": "Catching top-level Exception obscures specific failure modes.",
        "suggestion": "Catch specific exceptions like IOException, ValueError, or NotFoundException.",
    },
]

# ── 3. PERFORMANCE ANALYSIS RULES ────────────────────────────────────────
PERFORMANCE_RULES = [
    {
        "id": "PERF-001",
        "name": "Blocking Call in Async Context",
        "severity": Severity.HIGH,
        "category": FindingCategory.PERFORMANCE,
        "source": "performance",
        "pattern": re.compile(
            r"""(?i)async\s+def\s+\w+[\s\S]*?(?:time\.sleep|requests\.(?:get|post|put|delete)|urllib\.request)""",
            re.MULTILINE,
        ),
        "title": "Synchronous Blocking Call in Async Function",
        "description": "Synchronous I/O or sleep inside an async event loop halts all concurrent requests.",
        "suggestion": "Use asyncio.sleep() or async HTTP clients (httpx, aiohttp).",
    },
    {
        "id": "PERF-002",
        "name": "N+1 Query Risk in Loop",
        "severity": Severity.MEDIUM,
        "category": FindingCategory.PERFORMANCE,
        "source": "performance",
        "pattern": re.compile(
            r"""(?:for|while)\s*\(?.*?\)?\s*[:{][\s\S]*?(?:\.findById|\.findOne|\.select|SELECT\s+.*FROM)""",
            re.MULTILINE,
        ),
        "title": "Potential N+1 Database Query in Loop",
        "description": "Database queries issued sequentially inside loop iterations cause severe latency.",
        "suggestion": "Batch query with IN clause, JOIN fetch, or bulk lookup.",
    },
]

# ── 4. BUG DETECTION RULES ───────────────────────────────────────────────
BUG_RULES = [
    {
        "id": "BUG-001",
        "name": "Resource Leak (Unclosed stream/file)",
        "severity": Severity.HIGH,
        "category": FindingCategory.BUG,
        "source": "bug",
        "pattern": re.compile(
            r"""(?i)(?:open\s*\([^)]+\)(?![\s\S]*?(?:close\(\)|with\s+open))|new\s+FileInputStream\s*\()""",
            re.MULTILINE,
        ),
        "title": "Potential Resource Leak",
        "description": "Opened resource without guarantee of cleanup or try-with-resources / with block.",
        "suggestion": "Use 'with open(...) as f:' in Python or 'try (var f = ...)' in Java.",
    },
]


def run_static_analysis(file_path: str, content: str) -> list[Finding]:
    """Execute all static analysis tools on a given file's content.

    Runs Semgrep rules, SonarQube rules, Performance checks, Bug checks,
    and Python AST syntax verification when applicable.
    """
    findings: list[Finding] = []
    lines = content.splitlines()

    # Rule collections
    all_rules = SEMGREP_RULES + SONARQUBE_RULES + PERFORMANCE_RULES + BUG_RULES

    for rule in all_rules:
        pattern: re.Pattern = rule["pattern"]
        for match in pattern.finditer(content):
            # Calculate line number
            start_pos = match.start()
            line_no = content[:start_pos].count("\n") + 1
            snippet = lines[line_no - 1].strip() if line_no <= len(lines) else match.group(0)[:80]

            findings.append(
                Finding(
                    severity=rule["severity"],
                    category=rule["category"],
                    source=rule["source"],
                    file=file_path,
                    line=line_no,
                    title=rule["title"],
                    description=rule["description"],
                    suggestion=rule["suggestion"],
                    code_snippet=snippet,
                    rule_id=rule["id"],
                )
            )

    # ── 5. SYNTAX ANALYSIS (AST Verification for Python) ──────────────────
    if file_path.endswith(".py"):
        try:
            ast.parse(content, filename=file_path)
        except SyntaxError as e:
            findings.append(
                Finding(
                    severity=Severity.CRITICAL,
                    category=FindingCategory.SYNTAX,
                    source="syntax",
                    file=file_path,
                    line=e.lineno or 1,
                    title=f"Python Syntax Error: {e.msg}",
                    description=f"File contains invalid Python syntax at line {e.lineno}: {e.text or ''}",
                    suggestion="Fix syntax error before committing or merging.",
                    code_snippet=e.text.strip() if e.text else None,
                    rule_id="SYN-001",
                )
            )

    return findings
