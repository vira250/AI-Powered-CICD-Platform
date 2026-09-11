"""
Pipeline Planner — determines CI/CD Stages and Execution Strategy based on detected technologies.

Implements the 3rd stage of the Pipeline Generation Architecture:
  1. Determine CI/CD Stages:
     - Setup / Install
     - Lint / Code Analysis
     - Build
     - Test
     - Security Scan
     - Packaging / Docker
     - Deploy
  2. Determine Execution Strategy:
     - Runner & OS
     - Permissions (least-privilege block)
     - Caching Strategy
     - Timeout Constraints (timeout-minutes)
     - Concurrency Controls (cancel-in-progress)
     - Matrix Strategy
"""

from typing import Any, Optional


def determine_ci_cd_stages(tech: dict[str, Any], structure: Optional[dict[str, Any]] = None) -> list[dict[str, Any]]:
    """
    Determine CI/CD Stages based on detected technologies and structure.
    Stages: Setup, Lint, Build, Test, Security, Docker (if present), Deploy.
    """
    language = tech.get("language", "Unknown")
    framework = tech.get("framework", "None")
    build_tool = tech.get("build_tool", "Unknown")
    test_framework = tech.get("test_framework", "None")
    has_docker = tech.get("has_docker", False)
    java_ver = tech.get("java_version", "17")
    node_ver = tech.get("node_version", "20")
    py_ver = tech.get("python_version", "3.12")

    stages: list[dict[str, Any]] = []

    # 1. Setup / Install Stage
    setup_steps = [{"name": "Checkout source code", "action": "actions/checkout@v4"}]
    if language == "Java":
        setup_steps.append({
            "name": "Set up JDK",
            "action": "actions/setup-java@v4",
            "with": {
                "distribution": "temurin",
                "java-version": java_ver,
                "cache": "maven" if build_tool == "Maven" else "gradle",
            },
        })
    elif language in ("JavaScript", "TypeScript"):
        setup_steps.append({
            "name": "Set up Node.js",
            "action": "actions/setup-node@v4",
            "with": {
                "node-version": node_ver,
                "cache": build_tool.lower() if build_tool in ("npm", "yarn", "pnpm") else "npm",
            },
        })
        setup_steps.append({"name": "Install dependencies", "run": _install_cmd(build_tool)})
    elif language == "Python":
        setup_steps.append({
            "name": "Set up Python",
            "action": "actions/setup-python@v5",
            "with": {
                "python-version": py_ver,
                "cache": "pip",
            },
        })
        setup_steps.append({"name": "Install dependencies", "run": _python_install_cmd(build_tool)})
    elif language == "Go":
        setup_steps.append({
            "name": "Set up Go",
            "action": "actions/setup-go@v5",
            "with": {"go-version": "1.22", "cache": True},
        })
    elif language == "Rust":
        setup_steps.append({
            "name": "Set up Rust toolchain",
            "action": "dtolnay/rust-toolchain@stable",
        })

    stages.append({
        "id": "setup",
        "name": "Setup & Dependencies",
        "steps": setup_steps,
        "description": f"Check out code and configure runtime environment ({language})",
    })

    # 2. Lint / Code Analysis Stage
    lint_cmd = _lint_cmd(language, build_tool)
    if lint_cmd:
        stages.append({
            "id": "lint",
            "name": "Lint & Code Quality",
            "steps": [{"name": "Execute static analysis / linter", "run": lint_cmd}],
            "description": f"Validate code style and static analysis for {language}",
        })

    # 3. Build Stage
    build_cmd = _build_cmd(language, build_tool, framework)
    stages.append({
        "id": "build",
        "name": "Build & Compilation",
        "steps": [{"name": "Build project artifacts", "run": build_cmd}],
        "description": f"Compile and build project artifacts using {build_tool}",
    })

    # 4. Test Stage
    test_cmd = _test_cmd(language, build_tool, test_framework)
    stages.append({
        "id": "test",
        "name": "Automated Testing",
        "steps": [{"name": f"Run automated tests ({test_framework})", "run": test_cmd}],
        "description": f"Run test suite using {test_framework}",
    })

    # 5. Security Scan Stage
    stages.append({
        "id": "security",
        "name": "Security & Vulnerability Scan",
        "steps": [
            {
                "name": "Initialize CodeQL Analysis",
                "action": "github/codeql-action/init@v3",
                "with": {"languages": _codeql_language(language)},
            },
            {
                "name": "Perform CodeQL Analysis",
                "action": "github/codeql-action/analyze@v3",
            },
        ],
        "description": "Scan codebase for security vulnerabilities and secrets",
    })

    # 6. Packaging / Docker Stage (if Docker present)
    if has_docker:
        stages.append({
            "id": "docker",
            "name": "Container Packaging",
            "steps": [
                {"name": "Set up Docker Buildx", "action": "docker/setup-buildx-action@v3"},
                {"name": "Build Docker image", "run": "docker build -t ${{ github.repository }}:${{ github.sha }} ."},
            ],
            "description": "Build container image for artifact distribution",
        })

    # 7. Deployment Stage
    stages.append({
        "id": "deploy",
        "name": "Deployment",
        "steps": [
            {"name": "Deploy application", "run": "echo 'Deploying application to target environment'"}
        ],
        "condition": "github.ref == 'refs/heads/main' && github.event_name == 'push'",
        "description": "Trigger deployment on successful push to main branch",
    })

    return stages


def determine_execution_strategy(tech: dict[str, Any], structure: Optional[dict[str, Any]] = None) -> dict[str, Any]:
    """
    Determine Execution Strategy:
      - Runner / OS
      - Permissions (least-privilege permissions block)
      - Caching Strategy
      - Timeout Constraints
      - Concurrency Controls (cancel-in-progress)
      - Matrix Strategy
    """
    language = tech.get("language", "Unknown")
    build_tool = tech.get("build_tool", "Unknown")

    # Permissions
    permissions = {
        "contents": "read",
        "security-events": "write",
        "pull-requests": "read",
        "actions": "read",
    }

    # Caching strategy
    caching = {
        "enabled": True,
        "mechanism": "action_native",
        "cache_key": f"{language.lower()}-{build_tool.lower()}-cache",
        "notes": f"Leverages built-in caching in actions/setup-{language.lower() if language != 'Java' else 'java'}",
    }

    # Concurrency strategy
    concurrency = {
        "group": "${{ github.workflow }}-${{ github.ref }}",
        "cancel-in-progress": True,
    }

    # Timeouts
    timeout_minutes = 30

    # Matrix strategy (optional for multi-version testing)
    matrix = {}
    if language in ("JavaScript", "TypeScript"):
        matrix = {"node-version": ["18.x", "20.x"]}
    elif language == "Python":
        matrix = {"python-version": ["3.11", "3.12"]}

    return {
        "runner": "ubuntu-latest",
        "permissions": permissions,
        "caching": caching,
        "concurrency": concurrency,
        "timeout_minutes": timeout_minutes,
        "matrix": matrix,
    }


def plan_pipeline(tech: dict[str, Any], structure: Optional[dict[str, Any]] = None) -> dict[str, Any]:
    """
    Pipeline Planner entrypoint.
    Executes:
      1. Determine CI/CD Stages
      2. Determine Execution Strategy
    """
    # Normalize if called with wrapper
    if "tech" in tech and isinstance(tech["tech"], dict):
        structure = tech.get("structure")
        tech = tech["tech"]

    language = tech.get("language", "Unknown")
    framework = tech.get("framework", "None")
    build_tool = tech.get("build_tool", "Unknown")
    test_framework = tech.get("test_framework", "None")
    has_docker = tech.get("has_docker", False)

    stages = determine_ci_cd_stages(tech, structure)
    strategy = determine_execution_strategy(tech, structure)

    return {
        "language": language,
        "framework": framework,
        "build_tool": build_tool,
        "test_framework": test_framework,
        "has_docker": has_docker,
        "triggers": {
            "push": {"branches": ["main", "master"]},
            "pull_request": {"branches": ["main", "master"]},
            "workflow_dispatch": {},
        },
        "stages": stages,
        "execution_strategy": strategy,
        "environment": {
            "os": strategy["runner"],
            "timeout_minutes": strategy["timeout_minutes"],
            "permissions": strategy["permissions"],
            "concurrency": strategy["concurrency"],
        },
    }


def _install_cmd(build_tool: str) -> str:
    if build_tool == "Yarn":
        return "yarn install --frozen-lockfile"
    if build_tool == "pnpm":
        return "pnpm install --frozen-lockfile"
    return "npm ci"


def _python_install_cmd(build_tool: str) -> str:
    if build_tool == "Pipenv":
        return "pip install pipenv && pipenv install --dev"
    if "Poetry" in build_tool:
        return "pip install poetry && poetry install"
    return "pip install --upgrade pip && pip install -r requirements.txt"


def _lint_cmd(language: str, build_tool: str) -> Optional[str]:
    if language in ("JavaScript", "TypeScript"):
        prefix = "yarn" if build_tool == "Yarn" else ("pnpm" if build_tool == "pnpm" else "npm run")
        return f"{prefix} lint || true"
    if language == "Python":
        return "pip install flake8 && flake8 . --count --select=E9,F63,F7,F82 --show-source --statistics || true"
    if language == "Go":
        return "go vet ./..."
    if language == "Rust":
        return "cargo clippy -- -D warnings || true"
    return None


def _build_cmd(language: str, build_tool: str, framework: str) -> str:
    if language == "Java":
        return "mvn clean package -DskipTests" if build_tool == "Maven" else "./gradlew build -x test"
    if language in ("JavaScript", "TypeScript"):
        prefix = "yarn" if build_tool == "Yarn" else ("pnpm" if build_tool == "pnpm" else "npm run")
        return f"{prefix} build"
    if language == "Python":
        return "python -m compileall ."
    if language == "Go":
        return "go build -v ./..."
    if language == "Rust":
        return "cargo build --release"
    return "echo 'Build step - customize for repository'"


def _test_cmd(language: str, build_tool: str, test_framework: str) -> str:
    if language == "Java":
        return "mvn test" if build_tool == "Maven" else "./gradlew test"
    if language in ("JavaScript", "TypeScript"):
        prefix = "yarn" if build_tool == "Yarn" else ("pnpm" if build_tool == "pnpm" else "npm run")
        return f"{prefix} test -- --passWithNoTests"
    if language == "Python":
        if "pytest" in test_framework.lower():
            return "pytest --tb=short -q"
        return "python -m unittest discover"
    if language == "Go":
        return "go test -v ./..."
    if language == "Rust":
        return "cargo test"
    return "echo 'Add test command here'"


def _codeql_language(language: str) -> str:
    mapping = {
        "Java": "java",
        "Kotlin": "java",
        "JavaScript": "javascript",
        "TypeScript": "javascript",
        "Python": "python",
        "Go": "go",
        "Ruby": "ruby",
        "C#": "csharp",
        "C/C++": "cpp",
    }
    return mapping.get(language, "javascript")
