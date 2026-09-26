"""
Mock Production Environment Server
Simulates target runtime complete with switchable /health (HTTP 200 / 500)
and administrative endpoints for container swap simulation.
"""

import logging
from datetime import datetime, timezone
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from config import EngineConfig

logger = logging.getLogger("DeploymentEngine.MockProduction")


class ProductionSimulationApp:
    """
    Mockup server mimicking the live deployment environment.
    Complete with a switchable /health check endpoint (returning HTTP 200
    for healthy or HTTP 500 for simulated failure).
    """
    def __init__(self):
        self.is_healthy: bool = True
        self.active_version: str = f"{EngineConfig.REGISTRY_URL}/{EngineConfig.DEFAULT_APP_NAME}:v1.0.0-stable"
        self.error_message: str = "Internal Server Error: Database connection pool exhausted"
        self.app = FastAPI(title="Live Production Environment (Mock)")
        self._setup_routes()

    def _setup_routes(self):
        @self.app.get("/health")
        def health_check():
            if self.is_healthy:
                return JSONResponse(
                    status_code=200,
                    content={
                        "status": "UP",
                        "active_version": self.active_version,
                        "timestamp": datetime.now(timezone.utc).isoformat(),
                        "components": {
                            "database": "UP",
                            "cache_cluster": "UP",
                            "queue_workers": "UP"
                        }
                    }
                )
            else:
                return JSONResponse(
                    status_code=500,
                    content={
                        "status": "DOWN",
                        "active_version": self.active_version,
                        "error": self.error_message,
                        "timestamp": datetime.now(timezone.utc).isoformat(),
                        "components": {
                            "database": "DOWN (503 Service Unavailable)",
                            "cache_cluster": "DEGRADED"
                        }
                    }
                )

        @self.app.get("/version")
        def get_version():
            return {
                "active_version": self.active_version,
                "healthy": self.is_healthy,
                "environment": "production"
            }

        @self.app.post("/admin/deploy")
        async def admin_deploy(request: Request):
            data = await request.json()
            self.active_version = data.get("image_tag", self.active_version)
            logger.info(f"[MOCK PRODUCTION RUNTIME] Live container swapped. Active version is now: {self.active_version}")
            return {"status": "DEPLOYED", "active_version": self.active_version}

        @self.app.post("/admin/set-health")
        async def admin_set_health(request: Request):
            data = await request.json()
            self.is_healthy = data.get("healthy", True)
            if "error_message" in data:
                self.error_message = data["error_message"]
            state_str = "HEALTHY (HTTP 200)" if self.is_healthy else "FAILING (HTTP 500)"
            logger.info(f"[MOCK PRODUCTION RUNTIME] Health state toggled to: {state_str}")
            return {"healthy": self.is_healthy, "active_version": self.active_version}


production_simulation_instance = ProductionSimulationApp()
production_app = production_simulation_instance.app
