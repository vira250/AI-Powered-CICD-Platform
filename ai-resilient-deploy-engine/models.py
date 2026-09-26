"""
Data models, schemas, and enumerations for the Deployment & Resiliency Engine.
"""

from typing import Dict, Any, Optional
from datetime import datetime, timezone
from pydantic import BaseModel, Field, field_validator


class DeploymentStatus:
    PENDING = "PENDING"
    IN_PROGRESS = "IN_PROGRESS"
    SUCCESSFUL_DEPLOYMENT = "SUCCESSFUL_DEPLOYMENT"
    FAILED = "FAILED"
    ROLLED_BACK = "ROLLED_BACK"


class WebhookPayload(BaseModel):
    """
    Phase 1: Validated incoming webhook payload from CI/CD pipeline
    (e.g., GitHub Actions workflow completion event).
    """
    event: str = Field(default="workflow_run", description="Name of the triggering CI event")
    status: str = Field(..., description="Workflow execution status (must be 'completed')")
    conclusion: str = Field(..., description="Final pipeline conclusion ('success' or 'failure')")
    repository: str = Field(..., description="Repository full name, e.g., 'acme/service'")
    branch: str = Field(default="main", description="Source Git branch")
    commit_hash: str = Field(..., description="40-character or short Git commit SHA")
    build_id: str = Field(..., description="Unique CI pipeline build/run identifier")
    semantic_version: Optional[str] = Field(default="1.0.0", description="Semantic release version")
    trigger_by: Optional[str] = Field(default="ci-bot", description="Identity or actor that triggered the build")
    timestamp: Optional[str] = Field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    target_environment: str = Field(default="production", description="Target deployment stage")

    @field_validator("status")
    @classmethod
    def validate_status(cls, v: str) -> str:
        if v.lower() != "completed":
            raise ValueError(f"Invalid workflow status '{v}'. Expected 'completed'.")
        return v.lower()

    @field_validator("conclusion")
    @classmethod
    def validate_conclusion(cls, v: str) -> str:
        if v.lower() != "success":
            raise ValueError(f"Pipeline conclusion must be 'success' to trigger deployment. Received: '{v}'")
        return v.lower()

    @field_validator("commit_hash")
    @classmethod
    def validate_commit_hash(cls, v: str) -> str:
        clean = v.strip()
        if len(clean) < 7:
            raise ValueError(f"Commit hash must be at least 7 characters long. Received: '{v}'")
        return clean


class ImageMetadata(BaseModel):
    """Metadata generated after packaging and pushing Docker image to registry."""
    image_tag: str
    digest: str
    size_mb: float
    registry: str
    pushed_at: str
    build_duration_sec: float
    architecture: str = "linux/amd64"


class HealthCheckResult(BaseModel):
    """Status probe results from active production health monitoring."""
    healthy: bool
    status_code: int
    latency_ms: float
    response: Optional[Any] = None
    error: Optional[str] = None


class DeploymentRecord(BaseModel):
    """Persistent database representation of a single deployment lifecycle."""
    deployment_id: str
    build_id: str
    commit_hash: str
    repository: str
    branch: str
    target_image_tag: str
    previous_image_tag: Optional[str] = None
    status: str = DeploymentStatus.PENDING
    created_at: str
    updated_at: str
    health_checks_completed: int = 0
    total_health_checks: int = 12
    rollback_executed: bool = False
    failure_reason: Optional[str] = None
    image_metadata: Optional[Dict[str, Any]] = None
