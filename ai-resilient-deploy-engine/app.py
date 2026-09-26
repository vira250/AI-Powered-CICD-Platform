"""
Deployment Controller FastAPI Service.
Exposes Phase 1 Webhook Listener (POST /deploy-trigger) and state inspection endpoints.
"""

import uuid
import logging
from typing import Dict, Any, Optional
from datetime import datetime, timezone
from fastapi import FastAPI, HTTPException, BackgroundTasks, status

from config import EngineConfig
from models import WebhookPayload, DeploymentRecord
from database import VersionControlDB
from docker_builder import DockerPackager
from deployer import DeploymentRunner
from monitor import HealthMonitor

logger = logging.getLogger("DeploymentEngine.Controller")


class DeploymentManager:
    """
    Central Orchestrator coordinating all phases:
    - Phase 1: Webhook validation & ingestion
    - Phase 2: Docker packaging & registry push
    - Phase 3: Live container rollout & state tracking
    - Phase 4: Active background health monitoring
    - Phase 5: Self-healing automated rollback
    """
    def __init__(self, db: VersionControlDB = None):
        self.db = db or VersionControlDB()
        self.packager = DockerPackager()
        self.runner = DeploymentRunner()
        self.monitor = HealthMonitor()

    def process_deployment_pipeline(self, payload: WebhookPayload, background_tasks: Optional[BackgroundTasks] = None) -> Dict[str, Any]:
        """
        Executes end-to-end deployment lifecycle.
        Called upon receiving a verified GitHub Actions SUCCESS webhook.
        """
        deployment_id = f"dep-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}-{uuid.uuid4().hex[:6]}"
        logger.info(f"====================================================================")
        logger.info(f"  NEW DEPLOYMENT PIPELINE TRIGGERED | ID: {deployment_id}")
        logger.info(f"  Repo: {payload.repository} | Branch: {payload.branch} | Commit: {payload.commit_hash[:10]}")
        logger.info(f"  Build ID: {payload.build_id} | Target Environment: {payload.target_environment}")
        logger.info(f"====================================================================")

        # ----------------------------------------------------------------------
        # Phase 2: Automated Docker Packaging & Registry Push
        # ----------------------------------------------------------------------
        image_tag = self.packager.generate_image_tag(
            repository=payload.repository,
            semver=payload.semantic_version or "1.0.0",
            build_id=payload.build_id
        )
        build_metadata = self.packager.build_and_push_image(
            target_dir=EngineConfig.BASE_DIR,
            image_tag=image_tag,
            commit_hash=payload.commit_hash
        )

        # ----------------------------------------------------------------------
        # Phase 3: Production Deployment Runner & DB Registration
        # ----------------------------------------------------------------------
        record = DeploymentRecord(
            deployment_id=deployment_id,
            build_id=payload.build_id,
            commit_hash=payload.commit_hash,
            repository=payload.repository,
            branch=payload.branch,
            target_image_tag=image_tag,
            created_at=datetime.now(timezone.utc).isoformat(),
            updated_at=datetime.now(timezone.utc).isoformat(),
            image_metadata=build_metadata
        )
        self.db.register_deployment_start(record)
        self.runner.deploy_to_production(image_tag)

        # ----------------------------------------------------------------------
        # Phase 4 & 5: Health Monitoring & Automated Rollback
        # ----------------------------------------------------------------------
        def run_monitoring_job():
            self.monitor.execute_monitoring_lifecycle(
                deployment_id=deployment_id,
                target_image_tag=image_tag,
                db=self.db,
                deployment_runner=self.runner
            )

        if background_tasks:
            background_tasks.add_task(run_monitoring_job)
            return {
                "status": "ACCEPTED",
                "message": "Deployment initiated. Health monitoring active in background.",
                "deployment_id": deployment_id,
                "image_tag": image_tag,
                "monitor_checks": EngineConfig.HEALTH_CHECK_TOTAL_COUNT,
                "check_interval_seconds": EngineConfig.HEALTH_CHECK_INTERVAL_SEC
            }
        else:
            success = self.monitor.execute_monitoring_lifecycle(
                deployment_id=deployment_id,
                target_image_tag=image_tag,
                db=self.db,
                deployment_runner=self.runner
            )
            return {
                "status": "SUCCESS" if success else "ROLLED_BACK",
                "deployment_id": deployment_id,
                "image_tag": image_tag,
                "healthy": success
            }


# FastAPI Application Definition
app = FastAPI(
    title="AI-Driven Automated Deployment & Resiliency Engine",
    description="Automated CI/CD webhook ingestion, Docker packaging, live rollout, health monitoring, and self-healing rollback controller.",
    version=EngineConfig.VERSION
)

deployment_manager = DeploymentManager()


@app.get("/")
def read_root():
    return {
        "engine": EngineConfig.APP_NAME,
        "version": EngineConfig.VERSION,
        "current_active_version": deployment_manager.db.get_current_version(),
        "status": "OPERATIONAL"
    }


@app.post("/deploy-trigger", status_code=status.HTTP_202_ACCEPTED)
async def deploy_trigger(payload: WebhookPayload, background_tasks: BackgroundTasks):
    """
    Phase 1: Success Event Webhook Listener
    Accepts simulated JSON payload representing a 'Pipeline Result: SUCCESS' event
    coming from a GitHub Actions workflow. Validates parameters and triggers
    automated packaging, live deployment, active monitoring, and self-healing.
    """
    logger.info(f"[PHASE 1: WEBHOOK] Received valid deployment trigger from repo '{payload.repository}', build '{payload.build_id}'")
    result = deployment_manager.process_deployment_pipeline(payload, background_tasks=background_tasks)
    return result


@app.get("/deployments")
def get_deployments():
    """Returns full version history registry and deployment records."""
    return {
        "current_version": deployment_manager.db.get_current_version(),
        "previous_version": deployment_manager.db.get_previous_version(),
        "deployments": deployment_manager.db.get_all_deployments()
    }


@app.get("/deployments/{deployment_id}")
def get_deployment_status(deployment_id: str):
    """Fetches details and real-time status of a specific deployment."""
    record = deployment_manager.db.get_deployment(deployment_id)
    if not record:
        raise HTTPException(status_code=404, detail="Deployment ID not found")
    return record
