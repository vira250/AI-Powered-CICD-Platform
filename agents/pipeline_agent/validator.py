"""
Pipeline Validator — triple-engine validation for generated CI/CD pipelines:
  1. YAML Syntax & Structural Validation
  2. Security Validation (permissions, action pinning, script injection, secrets)
  3. Best Practice Validation (caching, timeouts, concurrency, step naming)

Implements the 5th and 6th stages of the Pipeline Generation Architecture:
  - Pipeline Validator
  - Validation Passed? decision check
"""

import re
import yaml
from typing import Any, Optional


DANGEROUS_EXPRESSIONS = [
    r"\$\{\{\s*github\.event\.issue\.title\s*\}\}",
    r"\$\{\{\s*github\.event\.issue\.body\s*\}\}",
    r"\$\{\{\s*github\.event\.pull_request\.title\s*\}\}",
    r"\$\{\{\s*github\.event\.pull_request\.body\s*\}\}",
    r"\$\{\{\s*github\.event\.comment\.body\s*\}\}",
    r"\$\{\{\s*github\.event\.review\.body\s*\}\}",
    r"\$\{\{\s*github\.head_ref\s*\}\}",
    r"\$\{\{\s*github\.event\.head_commit\.message\s*\}\}",
]


def validate_yaml_syntax(yaml_content: str) -> dict[str, Any]:
    """
    Validate YAML syntax, top-level keys, and GitHub Actions workflow schema.
    """
    errors: list[str] = []
    warnings: list[str] = []
    parsed: Optional[dict[str, Any]] = None

    if not yaml_content or not yaml_content.strip():
        return {
            "valid": False,
            "errors": ["Workflow YAML content is empty."],
            "warnings": [],
            "parsed": None,
        }

    # 1. Parse YAML safely
    try:
        parsed = yaml.safe_load(yaml_content)
    except yaml.YAMLError as e:
        return {
            "valid": False,
            "errors": [f"Invalid YAML syntax: {str(e)}"],
            "warnings": [],
            "parsed": None,
        }

    if not isinstance(parsed, dict):
        return {
            "valid": False,
            "errors": ["YAML root must be a mapping (dictionary)."],
            "warnings": [],
            "parsed": None,
        }

    # 2. Schema: 'name'
    if "name" not in parsed:
        warnings.append("Missing top-level 'name' field; consider adding a descriptive workflow name.")

    # 3. Schema: 'on' triggers
    # Note: PyYAML safe_load might treat unquoted 'on:' as boolean True
    has_triggers = "on" in parsed or True in parsed
    if not has_triggers:
        errors.append("Missing 'on' trigger section — workflow must specify when it runs (push, pull_request, etc.).")

    # 4. Schema: 'jobs'
    if "jobs" not in parsed:
        errors.append("Missing 'jobs' section — workflow must define at least one job.")
    else:
        jobs = parsed.get("jobs")
        if not isinstance(jobs, dict) or len(jobs) == 0:
            errors.append("'jobs' section must contain at least one job mapping.")
        else:
            for job_id, job_def in jobs.items():
                if not isinstance(job_def, dict):
                    errors.append(f"Job '{job_id}' must be a mapping definition.")
                    continue

                if "runs-on" not in job_def:
                    errors.append(f"Job '{job_id}' is missing required 'runs-on' runner definition.")

                if "steps" not in job_def:
                    errors.append(f"Job '{job_id}' is missing required 'steps' list.")
                elif not isinstance(job_def["steps"], list):
                    errors.append(f"Job '{job_id}' steps must be a list.")
                elif len(job_def["steps"]) == 0:
                    errors.append(f"Job '{job_id}' has an empty 'steps' list.")
                else:
                    for idx, step in enumerate(job_def["steps"]):
                        if not isinstance(step, dict):
                            errors.append(f"Job '{job_id}' step #{idx + 1} must be an object.")
                            continue
                        if "uses" not in step and "run" not in step:
                            errors.append(f"Job '{job_id}' step #{idx + 1} must contain either 'uses' or 'run'.")

    return {
        "valid": len(errors) == 0,
        "errors": errors,
        "warnings": warnings,
        "parsed": parsed,
    }


def validate_security(parsed: Optional[dict[str, Any]], yaml_content: str) -> dict[str, Any]:
    """
    Security Validation:
      - Explicit least-privilege permissions block
      - Action version pinning (disallow @main/@master or untagged)
      - Script injection detection in 'run:' blocks
      - Secret leakage in commands (echoing secrets)
      - Dangerous triggers (e.g. unconstrained pull_request_target)
    """
    issues: list[str] = []
    warnings: list[str] = []

    if not parsed or not isinstance(parsed, dict):
        return {"secure": False, "issues": ["Cannot run security validation on unparsed YAML."], "warnings": []}

    # 1. Check Permissions block
    has_top_permissions = "permissions" in parsed
    jobs = parsed.get("jobs", {})
    has_job_permissions = any(isinstance(j, dict) and "permissions" in j for j in jobs.values()) if isinstance(jobs, dict) else False

    if not has_top_permissions and not has_job_permissions:
        issues.append(
            "Security Risk: No explicit 'permissions' block specified. Workflow inherits default repository permissions (potentially write-all). Specify least-privilege permissions (e.g., 'permissions: contents: read')."
        )
    elif has_top_permissions and parsed.get("permissions") == "write-all":
        issues.append("Security Risk: 'permissions: write-all' detected. Use least-privilege permissions.")

    # 2. Check Action Version Pinning
    if isinstance(jobs, dict):
        for job_id, job_def in jobs.items():
            if not isinstance(job_def, dict):
                continue
            steps = job_def.get("steps", [])
            if isinstance(steps, list):
                for idx, step in enumerate(steps):
                    if isinstance(step, dict) and "uses" in step:
                        action_ref = str(step["uses"]).strip()
                        # Local actions or docker actions don't use @tag
                        if action_ref.startswith("./") or action_ref.startswith("docker://"):
                            continue
                        if "@" not in action_ref:
                            issues.append(
                                f"Security Risk: Job '{job_id}' step #{idx + 1} uses unpinned action '{action_ref}'. Pin actions to an exact tag (e.g., @v4) or commit SHA."
                            )
                        else:
                            action_name, version = action_ref.split("@", 1)
                            if version.lower() in ("main", "master", "latest"):
                                warnings.append(
                                    f"Security Warning: Job '{job_id}' step '{step.get('name', action_name)}' references mutable branch '{action_ref}'. Pin to a release tag (e.g., @v4) or SHA."
                                )

    # 3. Check Script Injection in 'run:' lines
    lines = yaml_content.splitlines()
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("run:") or (stripped.startswith("- run:") or "run: |" in stripped):
            # Check for direct interpolation of untrusted expressions
            for expr in DANGEROUS_EXPRESSIONS:
                if re.search(expr, line):
                    issues.append(
                        f"Line {i + 1}: High Security Risk (Script Injection) - Direct context interpolation in 'run:' command. Pass context variables via 'env:' block instead of inline ${{ ... }}."
                    )

    # 4. Check Secret Leakage in commands
    for i, line in enumerate(lines):
        if re.search(r"echo\s+.*?\$\{\{\s*secrets\..*?\}\}", line):
            issues.append(f"Line {i + 1}: Security Risk (Secret Leakage) - Echoing GitHub secret directly in shell step.")

    # 5. Check Dangerous Triggers
    triggers = parsed.get("on") or parsed.get(True) or {}
    if isinstance(triggers, dict) and "pull_request_target" in triggers:
        warnings.append(
            "Security Notice: 'pull_request_target' trigger detected. Ensure untrusted PR code is not checked out and executed with elevated workflow permissions."
        )

    return {
        "secure": len(issues) == 0,
        "issues": issues,
        "warnings": warnings,
    }


def validate_best_practices(parsed: Optional[dict[str, Any]], yaml_content: str) -> dict[str, Any]:
    """
    Best Practice Validation:
      - Dependency Caching (setup action cache or actions/cache)
      - Execution Timeouts (timeout-minutes)
      - Concurrency Controls (concurrency: cancel-in-progress: true)
      - Descriptive step naming
      - Code Checkout presence
    """
    passed = True
    warnings: list[str] = []
    recommendations: list[str] = []

    if not parsed or not isinstance(parsed, dict):
        return {"passed": False, "warnings": ["Cannot evaluate best practices on invalid YAML."], "recommendations": []}

    # 1. Concurrency Controls
    if "concurrency" not in parsed:
        recommendations.append(
            "Best Practice: Add 'concurrency: group: ${{ github.workflow }}-${{ github.ref }}, cancel-in-progress: true' to prevent duplicate pipeline runs on outdated commits."
        )

    # 2. Actions / Step Checks across jobs
    jobs = parsed.get("jobs", {})
    if isinstance(jobs, dict):
        has_checkout = False
        has_caching = "cache" in yaml_content.lower()

        for job_id, job_def in jobs.items():
            if not isinstance(job_def, dict):
                continue

            # Job timeout
            if "timeout-minutes" not in job_def:
                recommendations.append(
                    f"Best Practice: Job '{job_id}' should define 'timeout-minutes: 30' to avoid hanging jobs and unnecessary runner minute consumption."
                )

            steps = job_def.get("steps", [])
            if isinstance(steps, list):
                for idx, step in enumerate(steps):
                    if not isinstance(step, dict):
                        continue

                    # Check for checkout
                    if "actions/checkout" in str(step.get("uses", "")):
                        has_checkout = True

                    # Step names
                    if not step.get("name"):
                        warnings.append(
                            f"Best Practice: Job '{job_id}' step #{idx + 1} is missing a descriptive 'name:'. Descriptive step names enhance logs and debugging."
                        )

        if not has_checkout:
            warnings.append("Best Practice: No 'actions/checkout' step found. Most CI pipelines require checking out the repository.")

        if not has_caching:
            recommendations.append(
                "Best Practice: Enable package caching (e.g., 'cache: npm' in actions/setup-node or 'cache: maven' in actions/setup-java) to speed up builds."
            )

    return {
        "passed": len(warnings) == 0,
        "warnings": warnings,
        "recommendations": recommendations,
    }


def validate_pipeline(yaml_content: str) -> dict[str, Any]:
    """
    Combined Pipeline Validator executing all 3 verification engines:
      1. YAML Syntax & Structural Validation
      2. Security Validation
      3. Best Practice Validation

    Determines if 'Validation Passed?':
      True only if YAML syntax is strictly valid AND there are zero security blocking issues.
    """
    syntax_res = validate_yaml_syntax(yaml_content)
    parsed = syntax_res.get("parsed")

    security_res = validate_security(parsed, yaml_content)
    practices_res = validate_best_practices(parsed, yaml_content)

    # Decision: Validation Passed?
    passed = syntax_res["valid"] and security_res["secure"]

    all_errors = list(syntax_res["errors"]) + list(security_res["issues"])
    all_warnings = list(syntax_res["warnings"]) + list(security_res["warnings"]) + list(practices_res["warnings"])

    info: dict[str, Any] = {}
    if parsed and isinstance(parsed, dict):
        info["name"] = parsed.get("name", "Unnamed Workflow")
        jobs = parsed.get("jobs", {})
        if isinstance(jobs, dict):
            info["job_count"] = len(jobs)
            info["job_names"] = list(jobs.keys())
            for j_name, j_val in jobs.items():
                if isinstance(j_val, dict) and "steps" in j_val:
                    info[f"steps_in_{j_name}"] = len(j_val["steps"])

    return {
        "valid": passed,  # Validation Passed? (Yes / No)
        "passed": passed,
        "errors": all_errors,
        "warnings": all_warnings,
        "sections": {
            "yaml_syntax": {
                "valid": syntax_res["valid"],
                "errors": syntax_res["errors"],
                "warnings": syntax_res["warnings"],
            },
            "security_validation": {
                "secure": security_res["secure"],
                "issues": security_res["issues"],
                "warnings": security_res["warnings"],
            },
            "best_practice_validation": {
                "passed": practices_res["passed"],
                "warnings": practices_res["warnings"],
                "recommendations": practices_res["recommendations"],
            },
        },
        "info": info,
    }
