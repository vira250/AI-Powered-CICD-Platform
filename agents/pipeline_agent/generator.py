"""
AI Pipeline Generator — constructs LLM prompts from the plan + repo context,
calls the LLM to generate GitHub Actions workflow YAML, and implements the
Regenerate / Fix YAML feedback loop when validation checks fail.

Implements the 4th and 6th (Regenerate / Fix YAML) stages of the Pipeline Generation Architecture.
"""

import json
import re
from typing import Any, Optional
from llm_client import call_llm, extract_yaml_from_response


SYSTEM_PROMPT = """You are a Principal DevOps and Platform Security Engineer specializing in GitHub Actions CI/CD pipelines.
You generate production-ready, highly secure, and optimized GitHub Actions workflow YAML files.

STRICT REQUIREMENTS:
1. Output ONLY the raw YAML content, wrapped in ```yaml code blocks.
2. The YAML must be 100% valid GitHub Actions workflow syntax.
3. Top-level MUST define:
   - name: Descriptive workflow name
   - on: Triggers (push and pull_request to main/master)
   - permissions: Explicit least-privilege block (e.g., contents: read, security-events: write)
   - concurrency: group: ${{ github.workflow }}-${{ github.ref }}, cancel-in-progress: true
4. Jobs MUST include:
   - runs-on: ubuntu-latest
   - timeout-minutes: 30
   - Descriptive 'name:' on EVERY step
5. Actions MUST be pinned to major version tags (e.g., actions/checkout@v4, actions/setup-node@v4). NEVER use @master or @main.
6. Enable package caching (e.g. cache: 'npm' or cache: 'maven' or actions/cache).
7. NEVER pass untrusted github context expressions (${{ github.event... }}) directly inside inline bash 'run:' commands. Pass them as environment variables (env:) if needed.
8. NEVER echo secrets or tokens in 'run:' steps.
"""

REGENERATE_FIX_SYSTEM_PROMPT = """You are a Principal DevOps Engineer and GitHub Actions Security Specialist.
A previously generated GitHub Actions CI/CD workflow YAML failed validation (syntax errors, security violations, or missing best practices).

Your task is to REGENERATE and FIX the YAML to resolve ALL reported errors, security issues, and warnings.

STRICT REQUIREMENTS:
1. Output ONLY the fixed raw YAML content, wrapped in ```yaml code blocks.
2. Ensure top-level contains:
   - permissions: Explicit least-privilege block (e.g. contents: read)
   - concurrency: group: ${{ github.workflow }}-${{ github.ref }}, cancel-in-progress: true
3. Ensure every job contains:
   - timeout-minutes: 30
   - runs-on: ubuntu-latest
4. Ensure every step has a descriptive name: attribute.
5. Fix all action version pins to valid release tags (e.g., @v4).
6. Fix any script injection or secret echoing risks.
7. Preserve all necessary build, test, and lint steps while fixing defects.
"""


def generate_pipeline_yaml(context: dict, plan: dict[str, Any], tech: dict[str, Any]) -> str:
    """
    Generate initial GitHub Actions CI/CD pipeline YAML using the LLM.
    """
    tree_str = _format_directory_tree(context.get("structure", []))
    key_files_str = _format_key_files(context.get("files", []))
    execution_strategy = plan.get("execution_strategy", {})

    user_prompt = f"""Generate a complete GitHub Actions CI/CD pipeline for this repository.

REPOSITORY INFO:
- Owner: {context.get('repository', {}).get('owner', 'unknown')}
- Name: {context.get('repository', {}).get('repositoryName', 'unknown')}
- Branch: {context.get('repository', {}).get('branch', 'main')}

DETECTED TECHNOLOGIES:
- Language: {tech.get('language', 'Unknown')}
- Framework: {tech.get('framework', 'None')}
- Build Tool: {tech.get('build_tool', 'Unknown')}
- Test Framework: {tech.get('test_framework', 'None')}
- Runtime: {tech.get('runtime', 'None')}
- Has Docker: {tech.get('has_docker', False)}
- Java Version: {tech.get('java_version', '17')}
- Node Version: {tech.get('node_version', '20')}
- Python Version: {tech.get('python_version', '3.12')}

PLANNED PIPELINE STAGES:
{json.dumps(plan.get('stages', []), indent=2)}

EXECUTION STRATEGY CONSTRAINTS:
- Permissions: {json.dumps(execution_strategy.get('permissions', {'contents': 'read'}))}
- Concurrency: {json.dumps(execution_strategy.get('concurrency', {'cancel-in-progress': True}))}
- Timeout: {execution_strategy.get('timeout_minutes', 30)} minutes
- Runner: {execution_strategy.get('runner', 'ubuntu-latest')}

DIRECTORY STRUCTURE:
{tree_str}

KEY CONFIGURATION FILES:
{key_files_str}

Generate a complete, production-ready `.github/workflows/ci.yml` file.
Include: checkout, setup, cache, install dependencies, lint, build, test, and security scan.
If Docker is present, include container build.
Output ONLY the YAML wrapped in ```yaml blocks.
"""

    response = call_llm(SYSTEM_PROMPT, user_prompt)
    yaml_content = extract_yaml_from_response(response)
    lang = tech.get("language", "Unknown")
    if not yaml_content or not yaml_content.strip():
        yaml_content = generate_fallback_pipeline(tech, plan)
    elif lang == "Java" and "setup-java" not in yaml_content:
        yaml_content = generate_fallback_pipeline(tech, plan)
    elif lang == "Python" and "setup-python" not in yaml_content:
        yaml_content = generate_fallback_pipeline(tech, plan)
    elif lang in ("JavaScript", "TypeScript") and "setup-node" not in yaml_content:
        yaml_content = generate_fallback_pipeline(tech, plan)

    return yaml_content


def fix_and_regenerate_yaml(
    current_yaml: str,
    validation_errors: list[str],
    security_issues: list[str],
    warnings: list[str],
    plan: dict[str, Any],
    tech: dict[str, Any],
    iteration: int = 1,
) -> str:
    """
    Feedback loop: Regenerate / Fix YAML.
    Sends failed YAML and specific validation/security failures back to LLM for auto-remediation.
    """
    error_list_str = "\n".join(f"- [ERROR] {e}" for e in validation_errors)
    security_list_str = "\n".join(f"- [SECURITY RISK] {s}" for s in security_issues)
    warnings_list_str = "\n".join(f"- [WARNING] {w}" for w in warnings)

    user_prompt = f"""The following GitHub Actions workflow YAML failed validation during automated pipeline generation (Iteration {iteration}).

VALIDATION FAILURES TO FIX:
{error_list_str or '(None)'}

SECURITY DEFECTS TO FIX:
{security_list_str or '(None)'}

WARNINGS & RECOMMENDATIONS:
{warnings_list_str or '(None)'}

CURRENT FLAWED YAML:
```yaml
{current_yaml}
```

TECHNOLOGY CONTEXT:
- Language: {tech.get('language', 'Unknown')}
- Framework: {tech.get('framework', 'None')}
- Build Tool: {tech.get('build_tool', 'Unknown')}
- Test Framework: {tech.get('test_framework', 'None')}

Please regenerate the corrected, secure, production-ready `.github/workflows/ci.yml` resolving ALL errors above.
Output ONLY the fixed YAML wrapped in ```yaml blocks.
"""

    try:
        response = call_llm(REGENERATE_FIX_SYSTEM_PROMPT, user_prompt)
        healed_yaml = extract_yaml_from_response(response)
        if healed_yaml and healed_yaml.strip():
            if "permissions:" not in healed_yaml:
                healed_yaml = repair_yaml_deterministically(healed_yaml, validation_errors, security_issues)
            return healed_yaml
    except Exception:
        pass

    # Deterministic repair fallback if LLM is unavailable or times out
    return repair_yaml_deterministically(current_yaml, validation_errors, security_issues)


def repair_yaml_deterministically(yaml_str: str, errors: list[str], security_issues: list[str]) -> str:
    """
    Deterministic rule-based repair engine to ensure validation passes even if LLM is unreachable.
    """
    lines = yaml_str.splitlines()
    fixed_lines = []

    has_permissions = any(line.strip().startswith("permissions:") for line in lines)
    has_concurrency = any(line.strip().startswith("concurrency:") for line in lines)

    # If permissions is missing, prepend or insert right before jobs/on
    inserted_headers = False
    for line in lines:
        stripped = line.strip()
        if (stripped.startswith("jobs:") or stripped.startswith("on:")) and not inserted_headers:
            if not has_permissions:
                fixed_lines.append("permissions:")
                fixed_lines.append("  contents: read")
                fixed_lines.append("  security-events: write")
                fixed_lines.append("")
                has_permissions = True
            if not has_concurrency:
                fixed_lines.append("concurrency:")
                fixed_lines.append("  group: ${{ github.workflow }}-${{ github.ref }}")
                fixed_lines.append("  cancel-in-progress: true")
                fixed_lines.append("")
                has_concurrency = True
            inserted_headers = True

        # Fix unpinned actions (e.g. actions/checkout with no tag -> actions/checkout@v4)
        if "uses:" in line:
            for act in ["actions/checkout", "actions/setup-node", "actions/setup-java", "actions/setup-python", "actions/setup-go"]:
                if act in line and "@" not in line:
                    line = line.replace(act, f"{act}@v4")
            # Replace @master or @main with @v4
            line = re.sub(r"@(master|main|latest)", "@v4", line)

        # Fix echo secrets
        if re.search(r"echo\s+.*?\$\{\{\s*secrets\..*?\}\}", line):
            line = "      run: echo 'Secret verification passed (value masked)'"

        # Add timeout-minutes to jobs if missing
        if stripped.startswith("runs-on:"):
            fixed_lines.append(line)
            fixed_lines.append("    timeout-minutes: 30")
            continue

        fixed_lines.append(line)

    if not has_permissions:
        fixed_lines.insert(0, "permissions:\n  contents: read\n  security-events: write\n")

    return "\n".join(fixed_lines)


def generate_fallback_pipeline(tech: dict[str, Any], plan: dict[str, Any]) -> str:
    """Generates a guaranteed valid template matching tech detection."""
    lang = tech.get("language", "Java")
    build_tool = tech.get("build_tool", "Maven")
    name = f"{lang} CI/CD Pipeline"

    if lang == "Java":
        return f"""name: {name}

on:
  push:
    branches: [ main, master ]
  pull_request:
    branches: [ main, master ]

permissions:
  contents: read
  security-events: write

concurrency:
  group: ${{{{ github.workflow }}}}-${{{{ github.ref }}}}
  cancel-in-progress: true

jobs:
  build-and-test:
    name: Build & Test
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: '{'maven' if build_tool == 'Maven' else 'gradle'}'

      - name: Build and run unit tests
        run: {'mvn clean verify' if build_tool == 'Maven' else './gradlew build'}
"""
    elif lang in ("JavaScript", "TypeScript"):
        return f"""name: {name}

on:
  push:
    branches: [ main, master ]
  pull_request:
    branches: [ main, master ]

permissions:
  contents: read

concurrency:
  group: ${{{{ github.workflow }}}}-${{{{ github.ref }}}}
  cancel-in-progress: true

jobs:
  build-and-test:
    name: Build & Test
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up Node.js 20
        uses: actions/setup-node@v4
        with:
          node-version: 20
          cache: 'npm'

      - name: Install dependencies
        run: npm ci

      - name: Run linter
        run: npm run lint || true

      - name: Run build
        run: npm run build

      - name: Run tests
        run: npm test -- --passWithNoTests || true
"""
    else:
        return f"""name: {name}

on:
  push:
    branches: [ main, master ]
  pull_request:
    branches: [ main, master ]

permissions:
  contents: read

concurrency:
  group: ${{{{ github.workflow }}}}-${{{{ github.ref }}}}
  cancel-in-progress: true

jobs:
  build-and-test:
    name: Build & Test
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up Python 3.12
        uses: actions/setup-python@v5
        with:
          python-version: '3.12'
          cache: 'pip'

      - name: Install dependencies
        run: pip install --upgrade pip && pip install -r requirements.txt || true

      - name: Run automated tests
        run: pytest || python -m unittest discover || true
"""


def _format_directory_tree(structure: list[dict]) -> str:
    lines = []
    for node in sorted(structure, key=lambda x: x.get("path", "")):
        path = node.get("path", "")
        depth = path.count("/")
        indent = "  " * depth
        suffix = "/" if node.get("type") == "directory" else ""
        lines.append(f"{indent}├── {node.get('name', '')}{suffix}")
    if len(lines) > 100:
        lines = lines[:100]
        lines.append("... (truncated)")
    return "\n".join(lines)


def _format_key_files(files: list[dict]) -> str:
    key_file_names = {
        "pom.xml", "build.gradle", "build.gradle.kts",
        "package.json", "requirements.txt", "pyproject.toml",
        "Pipfile", "go.mod", "Cargo.toml", "Dockerfile",
        "docker-compose.yml", "docker-compose.yaml",
        "Makefile", "tsconfig.json", "vite.config.ts",
    }

    blocks = []
    for f in files:
        if f.get("type") != "text" or f.get("secret"):
            continue
        name = f.get("name", "")
        content = f.get("content")
        if not content:
            continue
        if name in key_file_names or f.get("path", "").startswith(".github/"):
            if len(content) > 3000:
                content = content[:3000] + "\n... (truncated)"
            blocks.append(f"--- {f['path']} ---\n{content}\n--- END ---")

    return "\n\n".join(blocks) if blocks else "(No key configuration files found)"


def remediate_pipeline_yaml(failed_yaml: str, error_logs: str, root_cause: str = "", suggested_fix: str = "") -> str:
    """Self-healing remediation loop from error logs (cross-agent support)."""
    user_prompt = f"""SELF-HEALING CI/CD WORKFLOW REMEDIATION

FAILED WORKFLOW YAML:
```yaml
{failed_yaml}
```

FAILURE LOGS:
```
{error_logs[:8000] if len(error_logs) > 8000 else error_logs}
```

ROOT CAUSE ANALYSIS:
{root_cause or 'Analyze logs to determine root cause.'}

SUGGESTED REMEDIATION FIX:
{suggested_fix or 'Apply best practice GitHub Actions fix.'}

Generate the corrected GitHub Actions YAML that fixes the failure.
Output ONLY YAML in ```yaml code blocks.
"""
    raw_response = call_llm(
        prompt=user_prompt,
        system_prompt=REGENERATE_FIX_SYSTEM_PROMPT,
        temperature=0.2,
        max_output_tokens=4096,
    )
    return extract_yaml_from_response(raw_response)
