"""Deployment Executor — Implements the Production Deployment, Health Check,
Rollback, and Version History lifecycle from the Architecture Flowchart.
"""

from __future__ import annotations
import uuid
import logging
from datetime import datetime, timezone
from typing import Any, Optional
from .models import DeploymentExecution, VersionHistory

log = logging.getLogger(__name__)

# In-memory version history registry keyed by "owner/repo"
_VERSION_REGISTRY: dict[str, VersionHistory] = {}


def get_version_history(owner: str, repo: str) -> VersionHistory:
    """Retrieve the current and previous version history for a repository."""
    key = f"{owner.lower()}/{repo.lower()}"
    if key not in _VERSION_REGISTRY:
        _VERSION_REGISTRY[key] = VersionHistory(repo_key=key, current_version=None, previous_versions=[])
    return _VERSION_REGISTRY[key]


def execute_deployment(
    owner: str,
    repo: str,
    commit_sha: str,
    environment: str = "production",
    image_tag: str | None = None,
    health_endpoint: str = "/health",
    simulate_health_failure: bool = False,
) -> DeploymentExecution:
    """Execute the full deployment lifecycle matching the flowchart:

    Step 1: Build / Use Image -> Docker Image
    Step 2: Deploy -> Production Environment
    Step 3: Health Check
    Step 4:
      - If Healthy -> Version History (Promote to Current Version)
      - If Failed -> Rollback -> Restore Previous Version from Version History
    """
    history = get_version_history(owner, repo)
    dep_id = f"dep-{uuid.uuid4().hex[:8]}"
    now_iso = datetime.now(timezone.utc).isoformat()
    version_label = f"v{len(history.previous_versions) + (1 if history.current_version else 1)}.{commit_sha[:7]}"
    docker_image = f"{owner.lower()}/{repo.lower()}:{image_tag or commit_sha[:7]}"

    execution = DeploymentExecution(
        deployment_id=dep_id,
        owner=owner,
        repo=repo,
        commit_sha=commit_sha,
        version=version_label,
        environment=environment,
        image_tag=docker_image,
        status="deployed",
        health_check_status="pending",
        health_endpoint=health_endpoint,
        deployed_at=now_iso,
        rolled_back=False,
    )

    # Step 3: Health Check
    is_healthy = not simulate_health_failure

    if is_healthy:
        execution.health_check_status = "healthy"
        execution.status = "healthy"
        execution.message = f"Successfully deployed {docker_image} to {environment}. Health check passed."

        # Update Version History (Current Version & Previous Versions)
        if history.current_version is not None:
            history.previous_versions.insert(0, history.current_version)
            if len(history.previous_versions) > 10:
                history.previous_versions = history.previous_versions[:10]

        history.current_version = execution

    else:
        # Step 4: Health Check FAILED -> Trigger Rollback!
        execution.health_check_status = "unhealthy"
        execution.status = "failed"
        execution.message = f"Deployment of {docker_image} failed health check at {health_endpoint}. Initiating rollback."

        # Check if there is a previous version to rollback to
        if history.current_version is not None:
            rollback_target = history.current_version
            execution.rolled_back = True
            execution.restored_version = rollback_target.version
            execution.status = "rolled_back"
            execution.message += f" Automatically rolled back to {rollback_target.version} ({rollback_target.image_tag})."
        elif history.previous_versions:
            rollback_target = history.previous_versions[0]
            execution.rolled_back = True
            execution.restored_version = rollback_target.version
            execution.status = "rolled_back"
            history.current_version = rollback_target
            execution.message += f" Automatically rolled back to {rollback_target.version} ({rollback_target.image_tag})."
        else:
            execution.message += " No previous version available in history to restore."

    return execution


def manual_rollback(owner: str, repo: str, target_version: str | None = None) -> DeploymentExecution:
    """Manually trigger a rollback to a previous version from Version History."""
    history = get_version_history(owner, repo)
    if not history.previous_versions:
        raise ValueError(f"No previous versions found in version history for {owner}/{repo}")

    # Find target or use most recent previous
    target: DeploymentExecution | None = None
    if target_version:
        for v in history.previous_versions:
            if v.version == target_version or v.deployment_id == target_version:
                target = v
                break
        if target is None:
            raise ValueError(f"Version {target_version} not found in history")
    else:
        target = history.previous_versions[0]

    # Swap current and target
    old_current = history.current_version
    history.current_version = target

    if old_current:
        # Move rolled-back current to previous
        history.previous_versions = [v for v in history.previous_versions if v.deployment_id != target.deployment_id]
        history.previous_versions.insert(0, old_current)

    now_iso = datetime.now(timezone.utc).isoformat()
    return DeploymentExecution(
        deployment_id=f"dep-rb-{uuid.uuid4().hex[:8]}",
        owner=owner,
        repo=repo,
        commit_sha=target.commit_sha,
        version=target.version,
        environment=target.environment,
        image_tag=target.image_tag,
        status="rolled_back",
        health_check_status="healthy",
        health_endpoint=target.health_endpoint,
        deployed_at=now_iso,
        rolled_back=True,
        restored_version=target.version,
        message=f"Manual rollback completed: Restored {target.version} ({target.image_tag}).",
    )
