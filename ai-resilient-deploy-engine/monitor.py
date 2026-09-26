"""
Phase 4 & 5: Automated Active Health Check Monitor & Resiliency Supervisor.
"""

import time
import logging
from typing import Dict, Any
import requests

from config import EngineConfig
from database import VersionControlDB
from deployer import DeploymentRunner

logger = logging.getLogger("DeploymentEngine.Monitor")


class HealthMonitor:
    """
    Automated Active Health Check Monitor & Resiliency Supervisor.
    Pings the target environment's /health endpoint every 5 seconds for
    1 minute (12 polling checks total). If any failure occurs, halts immediately
    and triggers emergency self-healing rollback.
    """
    def __init__(
        self,
        target_url: str = EngineConfig.PRODUCTION_BASE_URL,
        interval_sec: float = EngineConfig.HEALTH_CHECK_INTERVAL_SEC,
        total_checks: int = EngineConfig.HEALTH_CHECK_TOTAL_COUNT,
        timeout_sec: float = EngineConfig.HEALTH_CHECK_TIMEOUT_SEC,
    ):
        self.target_url = target_url.rstrip("/")
        self.interval_sec = interval_sec
        self.total_checks = total_checks
        self.timeout_sec = timeout_sec

    def check_health_once(self) -> Dict[str, Any]:
        """Performs a single probe to the production /health endpoint."""
        health_url = f"{self.target_url}/health"
        start_probe = time.time()
        try:
            resp = requests.get(health_url, timeout=self.timeout_sec)
            latency_ms = round((time.time() - start_probe) * 1000, 2)
            if resp.status_code == 200:
                return {
                    "healthy": True,
                    "status_code": resp.status_code,
                    "latency_ms": latency_ms,
                    "response": resp.json() if "application/json" in resp.headers.get("content-type", "") else resp.text
                }
            else:
                return {
                    "healthy": False,
                    "status_code": resp.status_code,
                    "latency_ms": latency_ms,
                    "error": f"Received non-200 HTTP status: {resp.status_code}"
                }
        except requests.exceptions.RequestException as e:
            latency_ms = round((time.time() - start_probe) * 1000, 2)
            return {
                "healthy": False,
                "status_code": 0,
                "latency_ms": latency_ms,
                "error": f"Connection exception: {str(e)}"
            }

    def execute_monitoring_lifecycle(
        self,
        deployment_id: str,
        target_image_tag: str,
        db: VersionControlDB,
        deployment_runner: DeploymentRunner
    ) -> bool:
        """
        Executes the full active monitoring loop (Phase 4).
        If any probe fails, immediately initiates Phase 5 Automated Self-Healing Rollback.
        """
        logger.info(f"━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        logger.info(f"[PHASE 4: HEALTH MONITOR] Starting active runtime observation window.")
        logger.info(f"[PHASE 4: HEALTH MONITOR] Target Endpoint: {self.target_url}/health")
        logger.info(f"[PHASE 4: HEALTH MONITOR] Plan: {self.total_checks} probes @ {self.interval_sec}s interval ({int(self.total_checks * self.interval_sec)}s total)")

        checks_passed = 0

        for check_index in range(1, self.total_checks + 1):
            time.sleep(self.interval_sec)
            probe = self.check_health_once()

            if probe["healthy"]:
                checks_passed += 1
                db.update_health_check_progress(deployment_id, checks_passed)
                logger.info(
                    f"[PHASE 4: HEALTH MONITOR] Probe [{check_index}/{self.total_checks}] "
                    f"PASSED (HTTP {probe['status_code']}, Latency: {probe['latency_ms']}ms)"
                )
            else:
                # ==============================================================================
                # PHASE 5: SELF-HEALING & AUTOMATED ROLLBACK LOGIC
                # ==============================================================================
                logger.error(f"********************************************************************")
                logger.error(f"[PHASE 5: RESILIENCY TRIGGER] HEALTH CHECK [{check_index}/{self.total_checks}] FAILED!")
                logger.error(f"[PHASE 5: RESILIENCY TRIGGER] Reason: {probe.get('error', 'Status ' + str(probe.get('status_code')))}")
                logger.error(f"[PHASE 5: RESILIENCY TRIGGER] Halting monitoring loop immediately. Initiating self-healing rollback...")
                logger.error(f"********************************************************************")

                self.trigger_emergency_rollback(
                    deployment_id=deployment_id,
                    failed_tag=target_image_tag,
                    failure_reason=probe.get("error", "HTTP 500 Failure"),
                    db=db,
                    deployment_runner=deployment_runner
                )
                return False

        # All 12 checks passed successfully
        logger.info(f"━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        logger.info(f"[PHASE 4: HEALTH MONITOR] ALL {self.total_checks}/{self.total_checks} PROBES RETURNED 200 OK.")
        logger.info(f"[PHASE 4: HEALTH MONITOR] Production runtime is 100% HEALTHY and STABLE.")
        db.finalize_successful_deployment(deployment_id)
        return True

    def trigger_emergency_rollback(
        self,
        deployment_id: str,
        failed_tag: str,
        failure_reason: str,
        db: VersionControlDB,
        deployment_runner: DeploymentRunner
    ) -> None:
        """
        Phase 5 Self-Healing:
        1. Queries local state tracker DB for immediate Previous Version tag.
        2. Swaps previous stable container tag back into production.
        3. Forces mock production environment to restore healthy state.
        4. Verifies restored health with immediate confirmation probe.
        5. Updates database state to ROLLED_BACK.
        """
        previous_stable_version = db.get_previous_version()

        if not previous_stable_version:
            # Fallback to default stable if no previous version registered
            previous_stable_version = f"{EngineConfig.REGISTRY_URL}/{EngineConfig.DEFAULT_APP_NAME}:v1.0.0-stable"
            logger.warning(f"[PHASE 5: ROLLBACK] No explicit previous tag in DB history. Defaulting to: {previous_stable_version}")
        else:
            logger.info(f"[PHASE 5: ROLLBACK] Retrieved previous stable tag from database: '{previous_stable_version}'")

        logger.info(f"[PHASE 5: ROLLBACK] Restoring container deployment to previous stable version: {previous_stable_version}")

        # Update production manifest and trigger rollback swap
        deployment_runner.update_manifest(previous_stable_version)

        # Notify mock production environment to revert active container and restore healthy state
        try:
            # 1. Update active version
            requests.post(
                f"{self.target_url}/admin/deploy",
                json={"image_tag": previous_stable_version, "rollback": True},
                timeout=4.0
            )
            # 2. Reset health state to healthy (since previous version is healthy)
            requests.post(
                f"{self.target_url}/admin/set-health",
                json={"healthy": True, "reason": "Restored stable previous container"},
                timeout=4.0
            )
        except Exception as e:
            logger.warning(f"[PHASE 5: ROLLBACK] Notice during production rollback call: {e}")

        # Execute immediate verification check on restored container
        time.sleep(0.5)
        verify_probe = self.check_health_once()
        if verify_probe["healthy"]:
            logger.info(f"[PHASE 5: ROLLBACK] Post-rollback health verification: PASSED (HTTP {verify_probe['status_code']}, {verify_probe['latency_ms']}ms).")
            logger.info(f"[PHASE 5: ROLLBACK] 100% PRODUCTION UPTIME SUCCESSFULLY RESTORED!")
        else:
            logger.error(f"[PHASE 5: ROLLBACK] Warning: Verification probe returned status {verify_probe['status_code']}.")

        # Record rollback in state tracker database
        db.record_rollback(
            deployment_id=deployment_id,
            restored_version=previous_stable_version,
            reason=failure_reason
        )
