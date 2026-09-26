"""
Database Tracker: VersionControlDB
Lightweight, thread-safe, file-based state tracker mimicking a production
version history registry database.
"""

import json
import logging
import threading
from pathlib import Path
from typing import Dict, Any, Optional, List
from datetime import datetime, timezone

from config import EngineConfig
from models import DeploymentRecord, DeploymentStatus

logger = logging.getLogger("DeploymentEngine.DB")


class VersionControlDB:
    """
    Lightweight, thread-safe, file-based state tracker mimicking a production
    version history registry database. Maintains atomic state records,
    current active version, and chronological rollout audit logs.
    """
    def __init__(self, db_path: Path = EngineConfig.DB_FILE_PATH):
        self.db_path = db_path
        self._lock = threading.Lock()
        self._ensure_db_initialized()

    def _ensure_db_initialized(self) -> None:
        """Initializes storage file if not present."""
        with self._lock:
            if not self.db_path.exists():
                initial_state = {
                    "current_version": f"{EngineConfig.REGISTRY_URL}/{EngineConfig.DEFAULT_APP_NAME}:v1.0.0-stable",
                    "previous_version": None,
                    "last_updated": datetime.now(timezone.utc).isoformat(),
                    "deployments": []
                }
                self.db_path.parent.mkdir(parents=True, exist_ok=True)
                with open(self.db_path, "w", encoding="utf-8") as f:
                    json.dump(initial_state, f, indent=2)
                logger.info(f"Initialized VersionControlDB at '{self.db_path}' with baseline version.")

    def _read_data(self) -> Dict[str, Any]:
        """Reads database contents with lock protection."""
        if not self.db_path.exists():
            return {"current_version": None, "previous_version": None, "deployments": []}
        with open(self.db_path, "r", encoding="utf-8") as f:
            return json.load(f)

    def _write_data(self, data: Dict[str, Any]) -> None:
        """Writes database contents atomically using temporary file rename."""
        data["last_updated"] = datetime.now(timezone.utc).isoformat()
        temp_file = self.db_path.with_suffix(".tmp")
        with open(temp_file, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
        temp_file.replace(self.db_path)

    def get_current_version(self) -> Optional[str]:
        """Returns the currently active live version tag."""
        with self._lock:
            data = self._read_data()
            return data.get("current_version")

    def get_previous_version(self) -> Optional[str]:
        """Returns the fallback stable previous version tag."""
        with self._lock:
            data = self._read_data()
            return data.get("previous_version")

    def register_deployment_start(self, record: DeploymentRecord) -> None:
        """Records initial deployment transition to IN_PROGRESS state."""
        with self._lock:
            data = self._read_data()
            record.previous_image_tag = data.get("current_version")
            record.status = DeploymentStatus.IN_PROGRESS
            record.updated_at = datetime.now(timezone.utc).isoformat()
            
            data["deployments"].append(record.model_dump())
            self._write_data(data)
            logger.info(f"[DB] Deployment '{record.deployment_id}' registered. State: IN_PROGRESS. Target Tag: {record.target_image_tag}")

    def update_health_check_progress(self, deployment_id: str, checks_passed: int) -> None:
        """Updates real-time health check count for an ongoing deployment."""
        with self._lock:
            data = self._read_data()
            for dep in data["deployments"]:
                if dep["deployment_id"] == deployment_id:
                    dep["health_checks_completed"] = checks_passed
                    dep["updated_at"] = datetime.now(timezone.utc).isoformat()
                    break
            self._write_data(data)

    def finalize_successful_deployment(self, deployment_id: str) -> None:
        """Marks deployment as successful and updates the current active version."""
        with self._lock:
            data = self._read_data()
            target_tag = None
            for dep in data["deployments"]:
                if dep["deployment_id"] == deployment_id:
                    dep["status"] = DeploymentStatus.SUCCESSFUL_DEPLOYMENT
                    dep["updated_at"] = datetime.now(timezone.utc).isoformat()
                    target_tag = dep["target_image_tag"]
                    break

            if target_tag:
                data["previous_version"] = data.get("current_version")
                data["current_version"] = target_tag
                self._write_data(data)
                logger.info(f"[DB] Deployment '{deployment_id}' SUCCESSFUL. Current Version -> '{target_tag}'. Previous -> '{data['previous_version']}'")

    def record_rollback(self, deployment_id: str, restored_version: str, reason: str) -> None:
        """Records emergency rollback and restores active version pointer."""
        with self._lock:
            data = self._read_data()
            for dep in data["deployments"]:
                if dep["deployment_id"] == deployment_id:
                    dep["status"] = DeploymentStatus.ROLLED_BACK
                    dep["rollback_executed"] = True
                    dep["failure_reason"] = reason
                    dep["updated_at"] = datetime.now(timezone.utc).isoformat()
                    break

            # Restore current version pointer
            data["current_version"] = restored_version
            self._write_data(data)
            logger.warning(f"[DB] Deployment '{deployment_id}' marked as ROLLED_BACK. Active Version restored to '{restored_version}'")

    def get_deployment(self, deployment_id: str) -> Optional[Dict[str, Any]]:
        """Fetches full state record for a given deployment ID."""
        with self._lock:
            data = self._read_data()
            for dep in data.get("deployments", []):
                if dep["deployment_id"] == deployment_id:
                    return dep
            return None

    def get_all_deployments(self) -> List[Dict[str, Any]]:
        """Returns all deployment records."""
        with self._lock:
            return self._read_data().get("deployments", [])
