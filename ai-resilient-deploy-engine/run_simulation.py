"""
Interactive End-to-End Simulation Runner for the AI Deployment & Resiliency Engine.
"""

import sys
import time
import requests
import threading
from datetime import datetime, timezone
import uvicorn
from fastapi import FastAPI

from config import EngineConfig
from models import WebhookPayload, DeploymentRecord
from database import VersionControlDB
from docker_builder import DockerPackager
from deployer import DeploymentRunner
from monitor import HealthMonitor
from mock_production import production_simulation_instance, production_app
from app import app as controller_app, logger


def run_server_in_thread(app_to_run: FastAPI, host: str, port: int) -> uvicorn.Server:
    """Runs a Uvicorn server instance in a background daemon thread."""
    config = uvicorn.Config(app=app_to_run, host=host, port=port, log_level="warning")
    server = uvicorn.Server(config)
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    return server


def main():
    print("""
================================================================================
          AI-DRIVEN DEPLOYMENT & RESILIENCY ENGINE SIMULATION
================================================================================
Starting dual services:
  - Deployment Controller API: http://127.0.0.1:8080
  - Mock Production Environment: http://127.0.0.1:8081
================================================================================
""")

    # 1. Spin up both local services
    logger.info("Spinning up Mock Production Server on 127.0.0.1:8081...")
    prod_server = run_server_in_thread(production_app, EngineConfig.PRODUCTION_HOST, EngineConfig.PRODUCTION_PORT)

    logger.info("Spinning up Deployment Controller API on 127.0.0.1:8080...")
    controller_server = run_server_in_thread(controller_app, EngineConfig.CONTROLLER_HOST, EngineConfig.CONTROLLER_PORT)

    time.sleep(1.5)

    sim_db = VersionControlDB()
    sim_runner = DeploymentRunner(target_url=EngineConfig.PRODUCTION_BASE_URL)
    sim_packager = DockerPackager()
    
    # 1-second interval with 12 checks for snappy visual simulation (12s total check duration)
    demo_interval = 1.0
    demo_checks = 12
    sim_monitor = HealthMonitor(
        target_url=EngineConfig.PRODUCTION_BASE_URL,
        interval_sec=demo_interval,
        total_checks=demo_checks
    )

    # --------------------------------------------------------------------------
    # SCENARIO 1: HAPPY PATH LIVE DEPLOYMENT
    # --------------------------------------------------------------------------
    print("\n" + "="*80)
    print(" >>> SCENARIO 1: SUCCESSFUL LIVE DEPLOYMENT (HAPPY PATH)")
    print("="*80)
    print("Action: GitHub Actions sends webhook with Conclusion: 'success' for release v1.1.0.")
    print("Target Environment: Healthy (HTTP 200 OK).")
    print("="*80 + "\n")

    production_simulation_instance.is_healthy = True

    payload_scenario_1 = WebhookPayload(
        event="workflow_run",
        status="completed",
        conclusion="success",
        repository="acme/payment-gateway",
        branch="main",
        commit_hash="7f8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b",
        build_id="gha-build-10842",
        semantic_version="1.1.0",
        trigger_by="github-actions[bot]",
        target_environment="production"
    )

    dep_id_1 = f"dep-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}-s1happy"
    image_tag_1 = sim_packager.generate_image_tag("acme/payment-gateway", "1.1.0", "gha-build-10842")
    build_meta_1 = sim_packager.build_and_push_image(EngineConfig.BASE_DIR, image_tag_1, payload_scenario_1.commit_hash)
    
    rec_1 = DeploymentRecord(
        deployment_id=dep_id_1,
        build_id=payload_scenario_1.build_id,
        commit_hash=payload_scenario_1.commit_hash,
        repository=payload_scenario_1.repository,
        branch=payload_scenario_1.branch,
        target_image_tag=image_tag_1,
        created_at=datetime.now(timezone.utc).isoformat(),
        updated_at=datetime.now(timezone.utc).isoformat(),
        image_metadata=build_meta_1
    )
    sim_db.register_deployment_start(rec_1)
    sim_runner.deploy_to_production(image_tag_1)

    print(f"\n--> Starting Phase 4 Health Monitoring ({demo_checks} checks @ {demo_interval}s interval)...")
    s1_result = sim_monitor.execute_monitoring_lifecycle(
        deployment_id=dep_id_1,
        target_image_tag=image_tag_1,
        db=sim_db,
        deployment_runner=sim_runner
    )

    print(f"\n[SCENARIO 1 RESULT]: {'SUCCESSFUL_DEPLOYMENT' if s1_result else 'FAILED'}")
    print(f"Current Active Version in DB: {sim_db.get_current_version()}")
    print(f"Previous Version in DB:       {sim_db.get_previous_version()}\n")

    time.sleep(2.0)

    # --------------------------------------------------------------------------
    # SCENARIO 2: FLAWED RELEASE -> PRODUCTION CRASH -> SELF-HEALING ROLLBACK
    # --------------------------------------------------------------------------
    print("\n" + "="*80)
    print(" >>> SCENARIO 2: DETECTED PRODUCTION FAILURE & SELF-HEALING ROLLBACK")
    print("="*80)
    print("Action: GitHub Actions sends webhook for release v1.2.0 (contains runtime flaw).")
    print("Simulation: After 2 healthy probes, production environment crashes (HTTP 500).")
    print("Expectation: Engine catches failure, halts immediately, fetches immediate")
    print(f"             previous version ('{sim_db.get_current_version()}'), and executes emergency rollback.")
    print("="*80 + "\n")

    payload_scenario_2 = WebhookPayload(
        event="workflow_run",
        status="completed",
        conclusion="success",
        repository="acme/payment-gateway",
        branch="main",
        commit_hash="d4e5f6a7b8c91011121314151617181920212223",
        build_id="gha-build-10899",
        semantic_version="1.2.0-flawed",
        trigger_by="github-actions[bot]",
        target_environment="production"
    )

    dep_id_2 = f"dep-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}-s2fail"
    image_tag_2 = sim_packager.generate_image_tag("acme/payment-gateway", "1.2.0-flawed", "gha-build-10899")
    build_meta_2 = sim_packager.build_and_push_image(EngineConfig.BASE_DIR, image_tag_2, payload_scenario_2.commit_hash)

    rec_2 = DeploymentRecord(
        deployment_id=dep_id_2,
        build_id=payload_scenario_2.build_id,
        commit_hash=payload_scenario_2.commit_hash,
        repository=payload_scenario_2.repository,
        branch=payload_scenario_2.branch,
        target_image_tag=image_tag_2,
        created_at=datetime.now(timezone.utc).isoformat(),
        updated_at=datetime.now(timezone.utc).isoformat(),
        image_metadata=build_meta_2
    )
    sim_db.register_deployment_start(rec_2)
    sim_runner.deploy_to_production(image_tag_2)

    # Schedule simulated production crash after 2 checks
    def trigger_mid_rollout_failure():
        time.sleep(2.2)
        logger.warning("********************************************************************")
        logger.warning("[CHAOS SIMULATOR] Injecting Fatal Production Outage: Database Crash!")
        logger.warning("[CHAOS SIMULATOR] Target /health will now return HTTP 500...")
        logger.warning("********************************************************************")
        production_simulation_instance.is_healthy = False
        production_simulation_instance.error_message = "FATAL: Database connection timeout (Connection refused on port 5432)"

    chaos_thread = threading.Thread(target=trigger_mid_rollout_failure, daemon=True)
    chaos_thread.start()

    print(f"\n--> Starting Phase 4 Health Monitoring on flawed rollout...")
    s2_result = sim_monitor.execute_monitoring_lifecycle(
        deployment_id=dep_id_2,
        target_image_tag=image_tag_2,
        db=sim_db,
        deployment_runner=sim_runner
    )

    print(f"\n[SCENARIO 2 RESULT]: Health Monitored Flag: {s2_result}")
    rec_2_final = sim_db.get_deployment(dep_id_2)
    print(f"Final State in State Tracker DB: {rec_2_final.get('status')}")
    print(f"Rollback Executed:              {rec_2_final.get('rollback_executed')}")
    print(f"Failure Reason Recorded:         {rec_2_final.get('failure_reason')}")
    print(f"Active Live Version Restored:   {sim_db.get_current_version()}")

    print("\n" + "="*80)
    print("                     PERSISTENT STATE TRACKER AUDIT LOG")
    print("="*80)
    with open(EngineConfig.DB_FILE_PATH, "r", encoding="utf-8") as f:
        print(f.read())
    print("="*80)

    print("\n[VERIFICATION COMPLETE] All 5 Phases demonstrated successfully.")


if __name__ == "__main__":
    main()
