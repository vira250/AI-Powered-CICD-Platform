"""Pydantic models for the Deployment Agent output."""

from pydantic import BaseModel, Field
from typing import Optional, Any


class HealthCheckConfig(BaseModel):
    endpoint: str = "/health"
    interval_seconds: int = 30
    timeout_seconds: int = 10
    retries: int = 3


class EnvironmentConfig(BaseModel):
    name: str = "production"  # staging, production
    port: int = 8080
    env_vars: dict[str, str] = {}
    health_check: HealthCheckConfig = HealthCheckConfig()


class RollbackStrategy(BaseModel):
    enabled: bool = True
    strategy: str = "previous_version"  # previous_version, blue_green, canary
    max_rollback_versions: int = 5


class DeploymentPlan(BaseModel):
    plan_id: str
    generated_at: str
    repository: dict
    technology: dict
    dockerfile: str
    docker_compose: str
    deployment_strategy: str  # rolling, blue_green, canary, recreate
    environments: list[EnvironmentConfig] = []
    rollback: RollbackStrategy = RollbackStrategy()
    notes: list[str] = []
    model: dict = {}


class DeploymentExecution(BaseModel):
    deployment_id: str
    owner: str
    repo: str
    commit_sha: str
    version: str
    environment: str = "production"
    image_tag: str
    status: str = "deployed"  # "deployed" | "healthy" | "failed" | "rolled_back"
    health_check_status: str = "pending"  # "healthy" | "unhealthy" | "pending"
    health_endpoint: str = "/health"
    deployed_at: str
    rolled_back: bool = False
    restored_version: Optional[str] = None
    message: str = ""


class VersionHistory(BaseModel):
    repo_key: str  # "owner/repo"
    current_version: Optional[DeploymentExecution] = None
    previous_versions: list[DeploymentExecution] = []
