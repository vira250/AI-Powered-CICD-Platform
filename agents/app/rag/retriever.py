"""Template store + retriever for the Pipeline Generation Agent.

A lightweight RAG: CI/CD workflow templates are embedded with metadata
(language, framework, build tool, features). Given the detected technology
stack, the retriever scores every template and returns the best match so the
LLM can ground the generated YAML on a proven template instead of
hallucinating one from scratch.

Templates shipped (per project spec):
  * 3 x Java Spring Boot (Maven, Gradle, Maven+Docker)
  * 2 x Node.js frontend (npm build/test, Docker build/deploy)
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

TEMPLATE_DIR = Path(__file__).parent / "templates"


@dataclass
class Template:
    name: str
    path: Path
    language: str
    framework: str = ""
    build_tool: str = ""
    features: set[str] = field(default_factory=set)
    content: str = ""


def _load_templates() -> list[Template]:
    return [
        Template(
            name="springboot-maven",
            path=TEMPLATE_DIR / "springboot-maven.yml",
            language="java", framework="spring-boot", build_tool="maven",
            features={"build", "test", "package"},
        ),
        Template(
            name="springboot-gradle",
            path=TEMPLATE_DIR / "springboot-gradle.yml",
            language="java", framework="spring-boot", build_tool="gradle",
            features={"build", "test", "package"},
        ),
        Template(
            name="springboot-maven-docker",
            path=TEMPLATE_DIR / "springboot-maven-docker.yml",
            language="java", framework="spring-boot", build_tool="maven",
            features={"build", "test", "security-scan", "docker", "deploy"},
        ),
        Template(
            name="node-frontend",
            path=TEMPLATE_DIR / "node-frontend.yml",
            language="javascript", framework="react", build_tool="npm",
            features={"install", "lint", "test", "build"},
        ),
        Template(
            name="node-frontend-docker",
            path=TEMPLATE_DIR / "node-frontend-docker.yml",
            language="javascript", framework="react", build_tool="npm",
            features={"install", "test", "build", "docker", "deploy"},
        ),
    ]


class TemplateRetriever:
    """Scores templates against a detected stack and returns the best one."""

    def __init__(self) -> None:
        self.templates = _load_templates()
        for t in self.templates:
            t.content = t.path.read_text(encoding="utf-8")

    def _score(self, t: Template, stack: dict) -> int:
        score = 0
        if t.language == stack.get("language"):
            score += 4
        if t.framework and t.framework == stack.get("framework"):
            score += 3
        if t.build_tool and t.build_tool == stack.get("build_tool"):
            score += 3
        # prefer templates that already include docker/deploy when the
        # project is containerised
        if stack.get("dockerized") and "docker" in t.features:
            score += 2
        return score

    def retrieve(self, stack: dict, top_k: int = 1) -> list[Template]:
        ranked = sorted(self.templates, key=lambda t: self._score(t, stack),
                        reverse=True)
        best = [t for t in ranked if self._score(t, stack) > 0]
        return (best or ranked)[:top_k]


retriever = TemplateRetriever()
