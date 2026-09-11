"""
Pipeline Generation Engine — Full Orchestration of the Pipeline Generation Agent Architecture:

Flow:
  1. Repository Analyzer:
     - Read Project Files
     - Analyze Project Structure
     - Analyze Dependencies
  2. Technology Detection:
     - Detect Language
     - Detect Framework
     - Detect Build Tool
     - Detect Test Framework
  3. Pipeline Planner:
     - Determine CI/CD Stages
     - Determine Execution Strategy
  4. AI Pipeline Generator:
     - LLM
     - Generate YAML
  5. Pipeline Validator:
     - Validate YAML
     - Security Validation
     - Best Practice Validation
  6. Validation Passed?
     - Yes  -> Final Workflow (.github/workflows/ci.yml)
     - No   -> Regenerate / Fix YAML feedback loop (back to AI Pipeline Generator)
"""

import time
import logging
from typing import Any, Optional

from pipeline_agent.analyzer import (
    read_project_files,
    analyze_project_structure,
    analyze_dependencies,
    detect_language,
    detect_framework,
    detect_build_tool,
    detect_test_framework,
    detect_runtime_versions,
    analyze_repository,
)
from pipeline_agent.planner import (
    determine_ci_cd_stages,
    determine_execution_strategy,
    plan_pipeline,
)
from pipeline_agent.generator import (
    generate_pipeline_yaml,
    fix_and_regenerate_yaml,
)
from pipeline_agent.validator import (
    validate_pipeline,
    validate_yaml_syntax,
    validate_security,
    validate_best_practices,
)

logger = logging.getLogger("pipeline_agent.engine")


class PipelineEngine:
    """
    Executes the end-to-end Pipeline Generation Agent workflow with iterative feedback loop.
    """

    def __init__(self, max_regenerate_attempts: int = 3):
        self.max_regenerate_attempts = max_regenerate_attempts

    def run(self, context: dict[str, Any]) -> dict[str, Any]:
        start_time = time.time()
        iterations_history: list[dict[str, Any]] = []

        # =========================================================================
        # STAGE 1: REPOSITORY ANALYZER
        # =========================================================================
        logger.info("[1/6] Executing Repository Analyzer...")
        file_data = read_project_files(context)
        structure_data = analyze_project_structure(context)
        dependency_data = analyze_dependencies(file_data["content_map"])

        # =========================================================================
        # STAGE 2: TECHNOLOGY DETECTION
        # =========================================================================
        logger.info("[2/6] Executing Technology Detection...")
        file_names = structure_data["file_names"]
        content_map = file_data["content_map"]
        dependencies = dependency_data["dependencies"]

        language = detect_language(file_names, content_map, dependencies)
        framework = detect_framework(content_map, dependencies)
        build_tool = detect_build_tool(file_names, content_map)
        test_framework = detect_test_framework(content_map, dependencies)
        runtime_versions = detect_runtime_versions(content_map)

        tech = {
            "language": language,
            "framework": framework,
            "build_tool": build_tool,
            "test_framework": test_framework,
            "has_docker": structure_data["has_docker"],
            "has_ci": structure_data["has_ci"],
            "java_version": runtime_versions["java_version"],
            "node_version": runtime_versions["node_version"],
            "python_version": runtime_versions["python_version"],
        }

        # =========================================================================
        # STAGE 3: PIPELINE PLANNER
        # =========================================================================
        logger.info("[3/6] Executing Pipeline Planner...")
        stages = determine_ci_cd_stages(tech, structure_data)
        execution_strategy = determine_execution_strategy(tech, structure_data)

        plan = {
            "language": language,
            "framework": framework,
            "build_tool": build_tool,
            "test_framework": test_framework,
            "has_docker": structure_data["has_docker"],
            "triggers": {
                "push": {"branches": ["main", "master"]},
                "pull_request": {"branches": ["main", "master"]},
                "workflow_dispatch": {},
            },
            "stages": stages,
            "execution_strategy": execution_strategy,
            "environment": {
                "os": execution_strategy["runner"],
                "timeout_minutes": execution_strategy["timeout_minutes"],
                "permissions": execution_strategy["permissions"],
                "concurrency": execution_strategy["concurrency"],
            },
        }

        # =========================================================================
        # STAGE 4: AI PIPELINE GENERATOR (Initial Generation)
        # =========================================================================
        logger.info("[4/6] Executing AI Pipeline Generator (LLM)...")
        current_yaml = generate_pipeline_yaml(context, plan, tech)

        # =========================================================================
        # STAGE 5 & 6: PIPELINE VALIDATOR & REGENERATE / FIX FEEDBACK LOOP
        # =========================================================================
        iteration = 1
        final_validation: dict[str, Any] = {}

        while iteration <= self.max_regenerate_attempts:
            logger.info(f"[5/6] Executing Pipeline Validator (Iteration {iteration}/{self.max_regenerate_attempts})...")
            validation = validate_pipeline(current_yaml)
            passed = validation["valid"]  # Validation Passed? check

            iteration_record = {
                "iteration": iteration,
                "passed": passed,
                "yaml_length": len(current_yaml),
                "errors_count": len(validation["errors"]),
                "warnings_count": len(validation["warnings"]),
                "errors": validation["errors"],
                "warnings": validation["warnings"],
                "sections": validation["sections"],
            }
            iterations_history.append(iteration_record)

            if passed:
                logger.info(f"Validation PASSED on iteration {iteration}!")
                final_validation = validation
                break

            # If Validation Passed == NO:
            logger.warning(
                f"Validation FAILED on iteration {iteration} with {len(validation['errors'])} errors. "
                "Triggering Regenerate / Fix YAML feedback loop..."
            )

            if iteration < self.max_regenerate_attempts:
                # STAGE 6: REGENERATE / FIX YAML (Feedback Loop back to AI Pipeline Generator)
                syntax_errors = validation["sections"]["yaml_syntax"]["errors"]
                security_issues = validation["sections"]["security_validation"]["issues"]
                warnings = validation["warnings"]

                current_yaml = fix_and_regenerate_yaml(
                    current_yaml=current_yaml,
                    validation_errors=syntax_errors,
                    security_issues=security_issues,
                    warnings=warnings,
                    plan=plan,
                    tech=tech,
                    iteration=iteration,
                )

            iteration += 1

        # Post-loop check: if still invalid after max attempts, run final deterministic repair
        if not final_validation or not final_validation.get("valid"):
            final_validation = validate_pipeline(current_yaml)
            if not final_validation["valid"]:
                from pipeline_agent.generator import repair_yaml_deterministically
                current_yaml = repair_yaml_deterministically(
                    current_yaml,
                    final_validation["sections"]["yaml_syntax"]["errors"],
                    final_validation["sections"]["security_validation"]["issues"],
                )
                final_validation = validate_pipeline(current_yaml)
                iterations_history.append({
                    "iteration": iteration,
                    "action": "deterministic_repair_applied",
                    "passed": final_validation["valid"],
                    "errors": final_validation["errors"],
                    "warnings": final_validation["warnings"],
                    "sections": final_validation["sections"],
                })

        elapsed_ms = int((time.time() - start_time) * 1000)

        status = "success" if final_validation.get("valid") else "warning"
        message = (
            f"Pipeline generated and validated successfully ({len(iterations_history)} iteration(s))"
            if final_validation.get("valid")
            else f"Pipeline generated with warnings after {len(iterations_history)} iteration(s)"
        )

        return {
            "status": status,
            "workflow_file": ".github/workflows/ci.yml",
            "yaml_content": current_yaml,
            "technology": tech,
            "plan": plan,
            "validation": final_validation,
            "validation_passed": final_validation.get("valid", False),
            "iterations_count": len(iterations_history),
            "iterations_history": iterations_history,
            "generation_time_ms": elapsed_ms,
            "message": message,
            "architecture_trace": {
                "repository_analyzer": {
                    "total_files": file_data["total_files"],
                    "manifest_files": list(file_data["manifest_files"].keys()),
                    "structure": {
                        "has_docker": structure_data["has_docker"],
                        "has_tests": structure_data["has_tests"],
                        "is_monorepo": structure_data["is_monorepo"],
                        "subprojects": structure_data["subprojects"],
                    },
                    "dependencies_count": len(dependencies),
                    "sample_dependencies": dependencies[:10],
                },
                "technology_detection": tech,
                "pipeline_planner": {
                    "stages_count": len(stages),
                    "stages": [s["name"] for s in stages],
                    "execution_strategy": execution_strategy,
                },
                "ai_pipeline_generator": {
                    "total_iterations": len(iterations_history),
                    "regenerated": len(iterations_history) > 1,
                },
                "pipeline_validator": {
                    "yaml_syntax": final_validation.get("sections", {}).get("yaml_syntax", {}),
                    "security_validation": final_validation.get("sections", {}).get("security_validation", {}),
                    "best_practice_validation": final_validation.get("sections", {}).get("best_practice_validation", {}),
                },
            },
        }


# Global engine singleton
pipeline_engine = PipelineEngine()
