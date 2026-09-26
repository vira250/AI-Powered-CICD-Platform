"""
Configuration module for the AI-Driven Deployment & Resiliency Engine.
"""

import os
from pathlib import Path

class EngineConfig:
    APP_NAME: str = "ai-resilient-deploy-engine"
    VERSION: str = "2.0.0"
    
    # Controller Settings
    CONTROLLER_HOST: str = os.getenv("CONTROLLER_HOST", "127.0.0.1")
    CONTROLLER_PORT: int = int(os.getenv("CONTROLLER_PORT", "8080"))
    
    # Target Production Environment Settings
    PRODUCTION_HOST: str = os.getenv("PRODUCTION_HOST", "127.0.0.1")
    PRODUCTION_PORT: int = int(os.getenv("PRODUCTION_PORT", "8081"))
    PRODUCTION_BASE_URL: str = f"http://{PRODUCTION_HOST}:{PRODUCTION_PORT}"
    
    # Health Monitoring Parameters (Phase 4 requirement: 12 checks @ 5s = 1 min)
    HEALTH_CHECK_INTERVAL_SEC: float = float(os.getenv("HEALTH_CHECK_INTERVAL", "5.0"))
    HEALTH_CHECK_TOTAL_COUNT: int = int(os.getenv("HEALTH_CHECK_COUNT", "12"))
    HEALTH_CHECK_TIMEOUT_SEC: float = float(os.getenv("HEALTH_CHECK_TIMEOUT", "3.0"))
    
    # Paths & Persistence
    BASE_DIR: Path = Path(__file__).resolve().parent
    DB_FILE_PATH: Path = BASE_DIR / "deployment_history.json"
    COMPOSE_FILE_PATH: Path = BASE_DIR / "docker-compose.prod.yml"
    
    # Registry Settings
    REGISTRY_URL: str = os.getenv("REGISTRY_URL", "registry.internal.cloud/acme")
    DEFAULT_APP_NAME: str = "acme-microservice"
