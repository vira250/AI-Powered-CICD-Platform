"""
Repository Analyzer & Technology Detection Engine.

Implements the first two stages of the Pipeline Generation Architecture:
  1. Repository Analyzer:
     - Read Project Files
     - Analyze Project Structure
     - Analyze Dependencies (deep manifest parsing for Maven, Gradle, npm, pip, poetry, go, cargo)
  2. Technology Detection:
     - Detect Language
     - Detect Framework
     - Detect Build Tool
     - Detect Test Framework
"""

import json
import re
import xml.etree.ElementTree as ET
from typing import Any

# Indicator mappings
LANGUAGE_INDICATORS = {
    "pom.xml": "Java",
    "build.gradle": "Java",
    "build.gradle.kts": "Kotlin",
    "package.json": "JavaScript",
    "requirements.txt": "Python",
    "pyproject.toml": "Python",
    "setup.py": "Python",
    "Pipfile": "Python",
    "go.mod": "Go",
    "Cargo.toml": "Rust",
    "Gemfile": "Ruby",
    "composer.json": "PHP",
    "*.csproj": "C#",
    "*.sln": "C#",
    "pubspec.yaml": "Dart",
    "CMakeLists.txt": "C/C++",
    "Makefile": "C/C++",
}

FRAMEWORK_INDICATORS = {
    # Java
    "spring-boot-starter": "Spring Boot",
    "spring-boot-starter-web": "Spring Boot",
    "org.springframework.boot": "Spring Boot",
    "io.quarkus": "Quarkus",
    "io.micronaut": "Micronaut",
    # JavaScript/TypeScript
    "next": "Next.js",
    "react": "React",
    "vue": "Vue.js",
    "nuxt": "Nuxt.js",
    "angular": "Angular",
    "@angular/core": "Angular",
    "express": "Express",
    "fastify": "Fastify",
    "svelte": "Svelte",
    "@sveltejs/kit": "SvelteKit",
    "vite": "Vite",
    "nest": "NestJS",
    "@nestjs/core": "NestJS",
    # Python
    "django": "Django",
    "flask": "Flask",
    "fastapi": "FastAPI",
    "streamlit": "Streamlit",
    "tornado": "Tornado",
    # Go
    "gin-gonic": "Gin",
    "echo": "Echo",
    "fiber": "Fiber",
    # Ruby
    "rails": "Ruby on Rails",
    # PHP
    "laravel": "Laravel",
    "symfony": "Symfony",
}

BUILD_TOOL_INDICATORS = {
    "pom.xml": "Maven",
    "build.gradle": "Gradle",
    "build.gradle.kts": "Gradle",
    "package.json": "npm",
    "yarn.lock": "Yarn",
    "pnpm-lock.yaml": "pnpm",
    "Makefile": "Make",
    "CMakeLists.txt": "CMake",
    "Cargo.toml": "Cargo",
    "go.mod": "Go Modules",
    "Pipfile": "Pipenv",
    "pyproject.toml": "Poetry/pip",
    "setup.py": "setuptools",
}

TEST_FRAMEWORK_INDICATORS = {
    # Java
    "junit-jupiter": "JUnit 5",
    "junit": "JUnit",
    "testng": "TestNG",
    "mockito": "Mockito",
    # JavaScript / TypeScript
    "jest": "Jest",
    "mocha": "Mocha",
    "vitest": "Vitest",
    "cypress": "Cypress",
    "playwright": "Playwright",
    # Python
    "pytest": "pytest",
    "unittest": "unittest",
    "nose2": "nose2",
    # Go
    "testing": "Go testing",
    "testify": "Testify",
    # Ruby
    "rspec": "RSpec",
}

RUNTIME_INDICATORS = {
    "Dockerfile": "Docker",
    "docker-compose.yml": "Docker Compose",
    "docker-compose.yaml": "Docker Compose",
    ".dockerignore": "Docker",
    "Procfile": "Heroku",
    "app.yaml": "Google Cloud",
    "serverless.yml": "Serverless",
    "vercel.json": "Vercel",
    "netlify.toml": "Netlify",
    "fly.toml": "Fly.io",
    "railway.json": "Railway",
    "render.yaml": "Render",
}


# ── 1. REPOSITORY ANALYZER SUB-COMPONENTS ─────────────────────────────────

def read_project_files(context: dict) -> dict[str, Any]:
    """Read Project Files: Extracts reviewable manifest contents and key source files."""
    files = context.get("files", [])
    content_map: dict[str, str] = {}
    manifest_files: dict[str, str] = {}

    target_manifests = {
        "pom.xml", "build.gradle", "build.gradle.kts",
        "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
        "requirements.txt", "pyproject.toml", "setup.py", "Pipfile",
        "go.mod", "Cargo.toml", "Dockerfile", "docker-compose.yml",
        "docker-compose.yaml", "Makefile", "tsconfig.json", "vite.config.ts",
        "vite.config.js", "next.config.js", "next.config.mjs"
    }

    for f in files:
        if f.get("type") == "text" and f.get("content") and not f.get("secret"):
            path = f.get("path", "")
            name = f.get("name", "")
            content = f.get("content", "")
            content_map[path] = content
            if name in target_manifests or path.startswith(".github/"):
                manifest_files[path] = content

    return {
        "content_map": content_map,
        "manifest_files": manifest_files,
        "total_files": len(files),
    }


def analyze_project_structure(context: dict) -> dict[str, Any]:
    """Analyze Project Structure: Analyzes directory layout and structural flags."""
    structure = context.get("structure", [])
    file_paths = [node.get("path", "") for node in structure]
    file_names = {node.get("name", "") for node in structure}
    dir_paths = [node.get("path", "") for node in structure if node.get("type") == "directory"]

    has_src = any(p == "src" or p.startswith("src/") for p in file_paths)
    has_tests = any(p in ("tests", "test", "__tests__") or "test" in p.lower() for p in file_paths)
    has_docker = any(name in file_names for name in ("Dockerfile", ".dockerignore", "docker-compose.yml", "docker-compose.yaml"))
    has_ci = any(p.startswith(".github/workflows/") for p in file_paths)

    # Monorepo / Multi-project detection
    is_monorepo = False
    subprojects = []
    for d in dir_paths:
        if d in ("backend", "frontend", "server", "client", "api", "web", "services", "packages"):
            is_monorepo = True
            subprojects.append(d)

    return {
        "file_paths": file_paths,
        "file_names": file_names,
        "dir_paths": dir_paths,
        "has_src": has_src,
        "has_tests": has_tests,
        "has_docker": has_docker,
        "has_ci": has_ci,
        "is_monorepo": is_monorepo,
        "subprojects": subprojects,
    }


def analyze_dependencies(content_map: dict[str, str]) -> dict[str, Any]:
    """Analyze Dependencies: Deep manifest inspection to extract dependency lists and plugins."""
    dependencies: list[str] = []
    dev_dependencies: list[str] = []
    scripts: dict[str, str] = {}
    plugins: list[str] = []

    # 1. Inspect package.json
    for path, content in content_map.items():
        if path.endswith("package.json"):
            try:
                pkg = json.loads(content)
                deps = pkg.get("dependencies", {})
                dev_deps = pkg.get("devDependencies", {})
                dependencies.extend(list(deps.keys()))
                dev_dependencies.extend(list(dev_deps.keys()))
                scripts.update(pkg.get("scripts", {}))
            except Exception:
                pass

    # 2. Inspect pom.xml (Maven)
    for path, content in content_map.items():
        if path.endswith("pom.xml"):
            # Regex fallback for pom dependencies
            dep_matches = re.findall(r"<artifactId>([^<]+)</artifactId>", content)
            dependencies.extend(dep_matches)
            plugin_matches = re.findall(r"<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>", content)
            for g, a in plugin_matches:
                if "plugin" in a:
                    plugins.append(f"{g}:{a}")

    # 3. Inspect requirements.txt / pyproject.toml
    for path, content in content_map.items():
        if path.endswith("requirements.txt"):
            for line in content.splitlines():
                line = line.strip()
                if line and not line.startswith("#"):
                    pkg_name = re.split(r"[><=~;]", line)[0].strip()
                    if pkg_name:
                        dependencies.append(pkg_name)
        elif path.endswith("pyproject.toml"):
            dep_section = re.findall(r'\[(?:tool\.poetry\.dependencies|project\.dependencies)\]([\s\S]*?)(?:\[|\Z)', content)
            for sec in dep_section:
                for line in sec.splitlines():
                    if "=" in line and not line.strip().startswith("#"):
                        pkg = line.split("=")[0].strip().strip('"').strip("'")
                        if pkg and pkg != "python":
                            dependencies.append(pkg)

    # 4. Inspect go.mod
    for path, content in content_map.items():
        if path.endswith("go.mod"):
            req_matches = re.findall(r"require\s*\(([\s\S]*?)\)", content)
            for req_block in req_matches:
                for line in req_block.splitlines():
                    parts = line.strip().split()
                    if parts and not parts[0].startswith("//"):
                        dependencies.append(parts[0])

    return {
        "dependencies": sorted(list(set(dependencies))),
        "dev_dependencies": sorted(list(set(dev_dependencies))),
        "scripts": scripts,
        "plugins": sorted(list(set(plugins))),
    }


# ── 2. TECHNOLOGY DETECTION SUB-COMPONENTS ─────────────────────────────────

def detect_language(file_names: set[str], content_map: dict[str, str], dependencies: list[str]) -> str:
    """Detect Language: Identifies the primary language."""
    # Check for TypeScript first
    if "tsconfig.json" in file_names or any(p.endswith((".ts", ".tsx")) for p in content_map):
        return "TypeScript"

    for indicator, lang in LANGUAGE_INDICATORS.items():
        if indicator.startswith("*."):
            ext = indicator[1:]
            if any(name.endswith(ext) for name in file_names):
                return lang
        elif indicator in file_names:
            return lang

    for path in content_map:
        if path.endswith(".py"):
            return "Python"
        if path.endswith(".java"):
            return "Java"
        if path.endswith(".go"):
            return "Go"
        if path.endswith(".rs"):
            return "Rust"
        if path.endswith((".js", ".jsx")):
            return "JavaScript"
        if path.endswith(".rb"):
            return "Ruby"
        if path.endswith(".php"):
            return "PHP"

    return "Unknown"


def detect_framework(content_map: dict[str, str], dependencies: list[str]) -> str:
    """Detect Framework: Identifies the framework from manifests & dependencies."""
    dep_str = " ".join(dependencies).lower()

    for indicator, framework in FRAMEWORK_INDICATORS.items():
        if indicator.lower() in dep_str:
            return framework

    for content in content_map.values():
        content_lower = content.lower()
        for indicator, framework in FRAMEWORK_INDICATORS.items():
            if indicator.lower() in content_lower:
                return framework

    return "None"


def detect_build_tool(file_names: set[str], content_map: dict[str, str]) -> str:
    """Detect Build Tool: Identifies build system (Maven, Gradle, npm, yarn, pnpm, cargo, etc.)."""
    if "pnpm-lock.yaml" in file_names:
        return "pnpm"
    if "yarn.lock" in file_names:
        return "Yarn"
    if "package.json" in file_names:
        return "npm"
    if "pom.xml" in file_names:
        return "Maven"
    if "build.gradle" in file_names or "build.gradle.kts" in file_names:
        return "Gradle"
    if "Cargo.toml" in file_names:
        return "Cargo"
    if "go.mod" in file_names:
        return "Go Modules"
    if "Makefile" in file_names:
        return "Make"
    if "CMakeLists.txt" in file_names:
        return "CMake"
    if "Pipfile" in file_names:
        return "Pipenv"
    if "pyproject.toml" in file_names:
        return "Poetry/pip"
    if "requirements.txt" in file_names or "setup.py" in file_names:
        return "pip"

    return "Unknown"


def detect_test_framework(content_map: dict[str, str], dependencies: list[str]) -> str:
    """Detect Test Framework: Identifies test framework from dependencies & manifests."""
    dep_str = " ".join(dependencies).lower()

    for indicator, framework in TEST_FRAMEWORK_INDICATORS.items():
        if indicator.lower() in dep_str:
            return framework

    for content in content_map.values():
        content_lower = content.lower()
        for indicator, framework in TEST_FRAMEWORK_INDICATORS.items():
            if indicator.lower() in content_lower:
                return framework

    return "None"


def detect_runtime_versions(content_map: dict[str, str]) -> dict[str, str]:
    """Extract runtime versions (Java, Node, Python) from config files."""
    java_ver = "17"
    node_ver = "20"
    python_ver = "3.12"

    # Java in pom.xml
    for path, content in content_map.items():
        if path.endswith("pom.xml"):
            m = re.search(r"<java\.version>(\d+)</java\.version>", content)
            if m:
                java_ver = m.group(1)
            else:
                m = re.search(r"<maven\.compiler\.source>(\d+)</maven\.compiler\.source>", content)
                if m:
                    java_ver = m.group(1)

        # Node in package.json
        if path.endswith("package.json"):
            m = re.search(r'"node":\s*"([^"]+)"', content)
            if m:
                clean = re.sub(r"[^\d.]", "", m.group(1))
                if clean:
                    node_ver = clean.split(".")[0]

        # Python in .python-version or pyproject.toml
        if path.endswith(".python-version"):
            python_ver = content.strip().split(".")[0] + "." + content.strip().split(".")[1]

    return {
        "java_version": java_ver,
        "node_version": node_ver,
        "python_version": python_ver,
    }


# ── 3. COMBINED REPOSITORY ANALYZER ───────────────────────────────────────

def analyze_repository(context: dict) -> dict[str, Any]:
    """
    Execute Repository Analyzer and Technology Detection according to the architecture:
      - Read Project Files
      - Analyze Project Structure
      - Analyze Dependencies
      - Detect Language
      - Detect Framework
      - Detect Build Tool
      - Detect Test Framework
    """
    # 1. Repository Analyzer
    file_data = read_project_files(context)
    structure_data = analyze_project_structure(context)
    dep_data = analyze_dependencies(file_data["content_map"])

    # 2. Technology Detection
    language = detect_language(structure_data["file_names"], file_data["content_map"], dep_data["dependencies"])
    framework = detect_framework(file_data["content_map"], dep_data["dependencies"])
    build_tool = detect_build_tool(structure_data["file_names"], file_data["content_map"])
    test_framework = detect_test_framework(file_data["content_map"], dep_data["dependencies"])
    runtimes = detect_runtime_versions(file_data["content_map"])

    runtime_service = "None"
    for indicator, service in RUNTIME_INDICATORS.items():
        if indicator in structure_data["file_names"]:
            runtime_service = service
            break

    tech = {
        "language": language,
        "framework": framework,
        "build_tool": build_tool,
        "test_framework": test_framework,
        "runtime": runtime_service,
        "has_docker": structure_data["has_docker"],
        "has_ci": structure_data["has_ci"],
        "java_version": runtimes["java_version"],
        "node_version": runtimes["node_version"],
        "python_version": runtimes["python_version"],
    }

    return {
        "tech": tech,
        "structure": structure_data,
        "dependencies": dep_data,
        "manifest_files": file_data["manifest_files"],
    }
