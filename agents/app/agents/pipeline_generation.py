"""Pipeline Generation Agent.

Implements the PipelineGeneration architecture:
  Repository Analyzer -> Technology Detection (rule-based, file extensions)
  -> Pipeline Planner -> RAG template retrieval -> LLM YAML generation
  -> Pipeline Validator (YAML syntax + best-practice checks, with one
     regenerate/fix cycle on failure) -> Final workflow file.
"""
from __future__ import annotations

import re
from typing import Any

import yaml

from .base import BaseAgent
from ..rag.retriever import retriever

# ---------------------------------------------------------------------------
# Rule-based technology detection (file extensions + manifest files)
# ---------------------------------------------------------------------------

_EXT_LANGUAGE = {
    ".java": "java", ".kt": "kotlin", ".py": "python", ".js": "javascript",
    ".jsx": "javascript", ".ts": "javascript", ".tsx": "javascript",
    ".go": "go", ".rb": "ruby", ".php": "php", ".cs": "csharp",
}

_MANIFEST_HINTS = {
    "pom.xml": {"language": "java", "build_tool": "maven"},
    "build.gradle": {"language": "java", "build_tool": "gradle"},
    "build.gradle.kts": {"language": "java", "build_tool": "gradle"},
    "package.json": {"language": "javascript", "build_tool": "npm"},
    "requirements.txt": {"language": "python", "build_tool": "pip"},
    "pyproject.toml": {"language": "python", "build_tool": "pip"},
    "manage.py": {"language": "python", "framework": "django"},
    "go.mod": {"language": "go", "build_tool": "go"},
    "dockerfile": {"dockerized": True},
}


def detect_stack(files: list[str]) -> dict[str, Any]:
    """Rule-based tech-stack detection driven purely by file names/extensions."""
    stack: dict[str, Any] = {
        "language": None, "framework": None, "build_tool": None,
        "dockerized": False, "frontend": False,
    }
    lang_votes: dict[str, int] = {}
    lowered = [f.lower() for f in files]

    for f in lowered:
        base = f.rsplit("/", 1)[-1]
        ext = "." + base.rsplit(".", 1)[-1] if "." in base else ""
        if ext in _EXT_LANGUAGE:
            lang_votes[_EXT_LANGUAGE[ext]] = lang_votes.get(_EXT_LANGUAGE[ext], 0) + 1
        if base in _MANIFEST_HINTS:
            for k, v in _MANIFEST_HINTS[base].items():
                stack[k] = v

    if lang_votes:
        stack["language"] = stack["language"] or max(lang_votes, key=lang_votes.get)

    # Framework refinement
    if stack["language"] == "java":
        stack["framework"] = stack["framework"] or "spring-boot"
        stack["build_tool"] = stack["build_tool"] or "maven"
    elif stack["language"] == "javascript":
        if any(f.endswith((".jsx", ".tsx")) for f in lowered) or "src/app.jsx" in lowered:
            stack["framework"] = "react"
            stack["frontend"] = True
        stack["build_tool"] = stack["build_tool"] or "npm"
    elif stack["language"] == "python":
        if any("flask" in f for f in lowered):
            stack["framework"] = "flask"
        stack["framework"] = stack["framework"] or "django" if stack.get("framework") == "django" else stack["framework"]

    return stack


class PipelineGenerationAgent(BaseAgent):
    name = "pipeline_generation"
    description = ("Detects the project tech stack (rule-based on file "
                   "extensions), retrieves the best matching CI/CD template "
                   "via the RAG store, and generates a validated GitHub "
                   "Actions workflow YAML.")

    SYSTEM = (
        "You are a CI/CD pipeline expert. You generate production-quality "
        "GitHub Actions workflow YAML files. You MUST ground your output on "
        "the provided reference template, adapting it to the detected tech "
        "stack. Output ONLY valid YAML — no markdown fences, no commentary."
    )

    # -- Pipeline Validator -------------------------------------------------
    @staticmethod
    def _validate(workflow: str) -> list[str]:
        """YAML syntax + basic best-practice validation. Returns problems."""
        problems: list[str] = []
        try:
            doc = yaml.safe_load(workflow)
        except yaml.YAMLError as exc:
            return [f"Invalid YAML: {exc}"]
        if not isinstance(doc, dict):
            return ["Workflow is not a YAML mapping"]
        if "jobs" not in doc or not doc["jobs"]:
            problems.append("Missing 'jobs' section")
        if True not in doc and "on" not in doc:  # YAML 1.1 parses 'on' as True
            problems.append("Missing 'on' trigger section")
        for job_name, job in (doc.get("jobs") or {}).items():
            if not isinstance(job, dict) or "steps" not in job:
                problems.append(f"Job '{job_name}' has no steps")
        return problems

    def _generate_with_llm(self, stack: dict, template_content: str,
                           template_name: str, extra: str = "") -> str:
        user = (
            f"Detected technology stack: {stack}\n"
            f"Reference template name: {template_name}\n\n"
            f"Reference template:\n{template_content}\n\n"
            "Generate the final GitHub Actions workflow YAML for this "
            "project. Include build, test and (when a Dockerfile exists) "
            "docker build/push stages. Keep the template's structure and "
            "best practices."
        )
        if extra:
            user += f"\n\nThe previous attempt failed validation: {extra}. Fix it."
        out = self.llm.chat(self.SYSTEM, user, temperature=0.1)
        # strip accidental markdown fences
        return re.sub(r"^```(?:yaml|yml)?\n|\n```$", "", out.strip(),
                      flags=re.MULTILINE).strip()

    def run(self, payload: dict[str, Any]) -> dict[str, Any]:
        files: list[str] = payload.get("files", [])
        stack = detect_stack(files)

        matches = retriever.retrieve(stack, top_k=1)
        template = matches[0]

        if self.llm.available():
            workflow = self._generate_with_llm(stack, template.content,
                                               template.name)
            problems = self._validate(workflow)
            regenerated = False
            if problems:
                # Regenerate / Fix cycle (see PipelineGeneration diagram)
                workflow = self._generate_with_llm(
                    stack, template.content, template.name,
                    extra="; ".join(problems))
                regenerated = True
                problems = self._validate(workflow)
        else:
            # No LLM key configured — fall back to the raw template so the
            # flow still works end-to-end for demos.
            workflow, problems, regenerated = template.content, [], False

        return {
            "agent": self.name,
            "stack": stack,
            "template_used": template.name,
            "workflow_path": ".github/workflows/ai-ci-cd.yml",
            "workflow_yaml": workflow,
            "validation": {
                "passed": not problems,
                "problems": problems,
                "regenerated": regenerated,
            },
        }
