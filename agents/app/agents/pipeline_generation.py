"""Pipeline Generation Agent.

Implements direct rule-based technology stack detection + LLM generation
for ANY backend framework and language without requiring pre-baked RAG templates:
  Repository Analyzer -> Rule-Based Tech Stack Detection (file extensions, manifests)
  -> LLM Generation Agent (Gemini Flash dynamically crafts custom YAML for the detected framework)
  -> Pipeline Validator (YAML syntax + structure checks with automatic self-healing loop)
  -> Final GitHub Actions Workflow YAML.
"""
from __future__ import annotations

import re
from typing import Any

import yaml

from .base import BaseAgent

# ---------------------------------------------------------------------------
# Comprehensive Rule-Based Technology & Framework Detection
# ---------------------------------------------------------------------------

_EXT_LANGUAGE = {
    ".java": "java", ".kt": "kotlin", ".py": "python",
    ".go": "go", ".rs": "rust", ".cs": "csharp",
    ".php": "php", ".rb": "ruby", ".ts": "typescript",
    ".js": "javascript", ".cpp": "cpp", ".c": "c",
    ".ex": "elixir", ".exs": "elixir", ".scala": "scala",
}

_MANIFEST_HINTS: dict[str, dict[str, Any]] = {
    "pom.xml": {"language": "java", "build_tool": "maven", "framework": "spring-boot"},
    "build.gradle": {"language": "java", "build_tool": "gradle", "framework": "spring-boot"},
    "build.gradle.kts": {"language": "kotlin", "build_tool": "gradle", "framework": "spring-boot"},
    "mvnw": {"has_wrapper": True, "build_tool": "maven"},
    "gradlew": {"has_wrapper": True, "build_tool": "gradle"},
    "requirements.txt": {"language": "python", "build_tool": "pip"},
    "pyproject.toml": {"language": "python", "build_tool": "poetry"},
    "pipfile": {"language": "python", "build_tool": "pipenv"},
    "manage.py": {"language": "python", "framework": "django"},
    "go.mod": {"language": "go", "build_tool": "go"},
    "cargo.toml": {"language": "rust", "build_tool": "cargo"},
    "composer.json": {"language": "php", "build_tool": "composer"},
    "artisan": {"language": "php", "framework": "laravel"},
    "gemfile": {"language": "ruby", "build_tool": "bundler"},
    "rakefile": {"language": "ruby"},
    "dockerfile": {"dockerized": True},
    "docker-compose.yml": {"dockerized": True},
    "docker-compose.yaml": {"dockerized": True},
}


def detect_stack(files: list[str]) -> dict[str, Any]:
    """Rule-based stack & framework detection driven by repository file tree and manifests."""
    stack: dict[str, Any] = {
        "language": None,
        "framework": None,
        "build_tool": None,
        "has_wrapper": False,
        "dockerized": False,
        "key_manifests": [],
    }
    lang_votes: dict[str, int] = {}
    lowered = [f.lower() for f in files]

    for f in lowered:
        base = f.rsplit("/", 1)[-1]
        ext = "." + base.rsplit(".", 1)[-1] if "." in base else ""

        # Tally language votes by file extensions
        if ext in _EXT_LANGUAGE:
            lang = _EXT_LANGUAGE[ext]
            lang_votes[lang] = lang_votes.get(lang, 0) + 1

        # Match manifests and configuration files
        if base in _MANIFEST_HINTS:
            stack["key_manifests"].append(base)
            for k, v in _MANIFEST_HINTS[base].items():
                stack[k] = v

        if base.endswith(".csproj") or base.endswith(".sln"):
            stack["language"] = "csharp"
            stack["framework"] = "aspnet-core"
            stack["build_tool"] = "dotnet"
            stack["key_manifests"].append(base)

    if lang_votes:
        stack["language"] = max(lang_votes, key=lang_votes.get)

    if not stack["language"]:
        stack["language"] = "java"

    # Framework and build tool refinement
    lang = stack.get("language")
    if lang in ("java", "kotlin"):
        stack["framework"] = stack.get("framework") or "spring-boot"
        stack["build_tool"] = stack.get("build_tool") or "maven"
        if any("gradlew" in f for f in lowered) or any("build.gradle" in f for f in lowered):
            stack["build_tool"] = "gradle"
            stack["has_wrapper"] = any("gradlew" in f for f in lowered)
        elif any("mvnw" in f for f in lowered) or any("pom.xml" in f for f in lowered):
            stack["build_tool"] = "maven"
            stack["has_wrapper"] = any("mvnw" in f for f in lowered)
    elif lang == "python":
        if any("manage.py" in f for f in lowered):
            stack["framework"] = "django"
        elif any("fastapi" in f for f in lowered) or any("main.py" in f for f in lowered):
            stack["framework"] = "fastapi"
        elif any("flask" in f for f in lowered) or any("app.py" in f for f in lowered):
            stack["framework"] = "flask"
        else:
            stack["framework"] = "python-service"
        stack["build_tool"] = stack.get("build_tool") or "pip"
    elif lang == "go":
        stack["framework"] = stack.get("framework") or "gin/standard"
        stack["build_tool"] = "go"
    elif lang == "rust":
        stack["framework"] = stack.get("framework") or "actix/tokio"
        stack["build_tool"] = "cargo"
    elif lang == "csharp":
        stack["framework"] = "aspnet-core"
        stack["build_tool"] = "dotnet"
    elif lang == "php":
        stack["framework"] = stack.get("framework") or ("laravel" if any("artisan" in f for f in lowered) else "php-service")
        stack["build_tool"] = "composer"
    elif lang == "ruby":
        stack["framework"] = stack.get("framework") or "rails"
        stack["build_tool"] = "bundler"

    if any("dockerfile" in f for f in lowered):
        stack["dockerized"] = True

    return stack


class PipelineGenerationAgent(BaseAgent):
    name = "pipeline_generation"
    description = ("Dynamically detects the backend tech stack and framework using rule-based "
                   "manifest/extension analysis, and uses the LLM to synthesize a production-ready "
                   "GitHub Actions CI/CD workflow YAML tailored to any framework.")

    SYSTEM = (
        "You are an expert Principal DevOps and CI/CD Automation Engineer. "
        "You generate production-quality, secure, and robust GitHub Actions workflow YAML files for any backend language or framework.\n\n"
        "GUIDELINES:\n"
        "1. TRIGGER: Trigger on push to [main, master, develop] and pull_request to [main, master].\n"
        "2. ENVIRONMENT: Use `ubuntu-latest` for runners.\n"
        "3. OFFICIAL ACTIONS: Use modern official actions with caching enabled where applicable (actions/checkout@v4, actions/setup-java@v4, actions/setup-python@v5, actions/setup-go@v5, actions/setup-dotnet@v4, docker/build-push-action@v5).\n"
        "4. BEST PRACTICES: Include proper steps: Checkout, Runtime Setup with Cache, Dependency Installation, Unit/Integration Tests, Build/Package artifacts (JAR/binary/wheel), and (if dockerized) Docker build/push stages.\n"
        "5. WRAPPERS: If Maven/Gradle wrappers (mvnw/gradlew) exist, grant execute permission (`chmod +x gradlew` or `chmod +x mvnw`) before invoking.\n"
        "6. OUTPUT FORMAT: Output ONLY valid, clean YAML. Do NOT wrap with markdown fences (no ```yaml), and include NO explanatory conversational text."
    )

    # -- Pipeline Validator -------------------------------------------------
    @staticmethod
    def _validate(workflow: str) -> list[str]:
        """YAML syntax + basic best-practice validation. Returns problems list."""
        problems: list[str] = []
        try:
            doc = yaml.safe_load(workflow)
        except yaml.YAMLError as exc:
            return [f"Invalid YAML syntax: {exc}"]
        if not isinstance(doc, dict):
            return ["Workflow root is not a YAML mapping"]
        if "jobs" not in doc or not doc["jobs"]:
            problems.append("Missing 'jobs' section")
        if True not in doc and "on" not in doc:  # YAML 1.1 parses 'on' as True
            problems.append("Missing 'on' trigger section")
        for job_name, job in (doc.get("jobs") or {}).items():
            if not isinstance(job, dict) or "steps" not in job:
                problems.append(f"Job '{job_name}' has no steps")
        return problems

    def _generate_with_llm(self, stack: dict, files: list[str], extra: str = "") -> tuple[str, dict]:
        manifests = [f for f in files if any(f.lower().endswith(m) for m in ["pom.xml", "build.gradle", "requirements.txt", "pyproject.toml", "go.mod", "cargo.toml", "dockerfile", ".csproj"])]
        user = (
            f"Detected Stack Profile:\n"
            f"  - Language: {stack.get('language')}\n"
            f"  - Framework: {stack.get('framework')}\n"
            f"  - Build Tool: {stack.get('build_tool')}\n"
            f"  - Wrapper Present: {stack.get('has_wrapper', False)}\n"
            f"  - Dockerized: {stack.get('dockerized', False)}\n"
            f"  - Key Manifests: {manifests or stack.get('key_manifests', [])}\n\n"
            f"Generate the complete GitHub Actions workflow YAML (.github/workflows/ai-ci-cd.yml) tailored precisely "
            f"for this {stack.get('framework')} / {stack.get('language')} project."
        )
        if extra:
            user += f"\n\nThe previous attempt failed validation with errors: {extra}. Please correct the YAML syntax and structure."

        out, usage = self.llm.chat_with_usage(self.SYSTEM, user, temperature=0.1)
        # Clean potential markdown fences
        workflow = re.sub(r"^```(?:yaml|yml)?\n|\n```$", "", out.strip(), flags=re.MULTILINE).strip()
        return workflow, usage

    def _fallback_template(self, stack: dict) -> str:
        """Dynamic fallback when LLM is unavailable or offline."""
        lang = stack.get("language", "java")
        btool = stack.get("build_tool", "maven")

        if lang == "python":
            return (
                "name: CI/CD Pipeline\non:\n  push:\n    branches: [main, master, develop]\n  pull_request:\n    branches: [main, master]\n"
                "jobs:\n  build-and-test:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n"
                "      - uses: actions/setup-python@v5\n        with:\n          python-version: '3.11'\n          cache: 'pip'\n"
                "      - run: pip install -r requirements.txt\n      - run: pytest || python -m unittest discover\n"
            )
        elif lang == "go":
            return (
                "name: CI/CD Pipeline\non:\n  push:\n    branches: [main, master, develop]\n  pull_request:\n    branches: [main, master]\n"
                "jobs:\n  build-and-test:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n"
                "      - uses: actions/setup-go@v5\n        with:\n          go-version: '1.22'\n"
                "      - run: go test -v ./...\n      - run: go build -v ./...\n"
            )
        elif lang == "rust":
            return (
                "name: CI/CD Pipeline\non:\n  push:\n    branches: [main, master, develop]\n  pull_request:\n    branches: [main, master]\n"
                "jobs:\n  build-and-test:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n"
                "      - uses: dtolnay/rust-toolchain@stable\n      - run: cargo test --verbose\n      - run: cargo build --release\n"
            )
        elif btool == "gradle":
            return (
                "name: CI/CD Pipeline\non:\n  push:\n    branches: [main, master, develop]\n  pull_request:\n    branches: [main, master]\n"
                "jobs:\n  build-and-test:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n"
                "      - uses: actions/setup-java@v4\n        with:\n          java-version: '17'\n          distribution: 'temurin'\n          cache: 'gradle'\n"
                "      - run: chmod +x gradlew\n      - run: ./gradlew test\n      - run: ./gradlew build\n"
            )
        else:
            return (
                "name: CI/CD Pipeline\non:\n  push:\n    branches: [main, master, develop]\n  pull_request:\n    branches: [main, master]\n"
                "jobs:\n  build-and-test:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n"
                "      - uses: actions/setup-java@v4\n        with:\n          java-version: '17'\n          distribution: 'temurin'\n          cache: 'maven'\n"
                "      - run: mvn -B test\n      - run: mvn -B package -DskipTests\n"
            )

    def run(self, payload: dict[str, Any]) -> dict[str, Any]:
        files: list[str] = payload.get("files", [])
        stack = detect_stack(files)

        total_tokens = 0
        total_prompt_tokens = 0
        total_completion_tokens = 0
        template_descriptor = f"{stack.get('language')}-{stack.get('framework')}-{stack.get('build_tool')}"

        if self.llm.available():
            workflow, usage = self._generate_with_llm(stack, files)
            total_tokens += usage.get("total_tokens", 0)
            total_prompt_tokens += usage.get("prompt_tokens", 0)
            total_completion_tokens += usage.get("completion_tokens", 0)

            problems = self._validate(workflow)
            regenerated = False
            if problems:
                # Self-healing loop on syntax/structure issues
                workflow, usage2 = self._generate_with_llm(stack, files, extra="; ".join(problems))
                total_tokens += usage2.get("total_tokens", 0)
                total_prompt_tokens += usage2.get("prompt_tokens", 0)
                total_completion_tokens += usage2.get("completion_tokens", 0)

                regenerated = True
                problems = self._validate(workflow)
        else:
            workflow, problems, regenerated = self._fallback_template(stack), [], False

        # Calculate cost based on Gemini Flash pricing
        # Input: $0.075 per 1M tokens, Output: $0.30 per 1M tokens
        credits_used = (total_prompt_tokens * 0.075 / 1_000_000) + (total_completion_tokens * 0.30 / 1_000_000)

        return {
            "agent": self.name,
            "stack": stack,
            "template_used": template_descriptor,
            "workflow_path": ".github/workflows/ai-ci-cd.yml",
            "workflow_yaml": workflow,
            "validation": {
                "passed": not problems,
                "problems": problems,
                "regenerated": regenerated,
            },
            "credits_used": credits_used,
            "total_tokens": total_tokens,
        }
