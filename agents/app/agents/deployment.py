"""Deployment Agent.

Implements the DeploymentAgent architecture:
  Successful CI/CD pipeline -> Environment Selection -> Deployment Manager
  -> build/use Docker image -> push to Docker Registry (versioned images)
  -> deploy to Production Environment -> Health Endpoint check
  -> Deployment Status:  success -> record Current Version in history
                         failure -> Rollback Manager restores previous version

The agent shells out to the Docker CLI (available in the agents container via
the mounted /var/run/docker.sock). Every action returns structured status so
the Spring Boot backend can persist deployment history.
"""
from __future__ import annotations

import subprocess
import time
from typing import Any

import httpx

from .base import BaseAgent
from ..config import settings


def _sh(cmd: list[str], timeout: int = 600) -> dict[str, Any]:
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True,
                              timeout=timeout)
        return {"ok": proc.returncode == 0, "stdout": proc.stdout[-4000:],
                "stderr": proc.stderr[-4000:], "code": proc.returncode}
    except (subprocess.TimeoutExpired, FileNotFoundError) as exc:
        return {"ok": False, "stdout": "", "stderr": str(exc), "code": -1}


class DeploymentAgent(BaseAgent):
    name = "deployment"
    description = ("Builds/pushes versioned Docker images, deploys to the "
                   "production environment, runs health checks and rolls back "
                   "to the previous version automatically on failure.")

    # ------------------------------------------------------------ helpers
    @staticmethod
    def _image_tag(repo: str, version: str) -> str:
        registry = settings.docker_registry.rstrip("/")
        user = settings.docker_registry_user
        name = repo.replace("/", "-").lower()
        prefix = f"{registry}/{user}/" if user else f"{registry}/"
        return f"{prefix}{name}:{version}"

    def _registry_login(self) -> dict[str, Any]:
        if not settings.docker_registry_user:
            return {"ok": True, "stdout": "no registry credentials — skipped"}
        return _sh(["docker", "login", settings.docker_registry,
                    "-u", settings.docker_registry_user,
                    "--password-stdin"],
                   timeout=60) if False else _sh_login()

    # -------------------------------------------------------------- stages
    def _build(self, workdir: str, image: str) -> dict[str, Any]:
        return _sh(["docker", "build", "-t", image, workdir])

    def _push(self, image: str) -> dict[str, Any]:
        return _sh(["docker", "push", image])

    def _deploy(self, image: str, container: str) -> dict[str, Any]:
        _sh(["docker", "rm", "-f", container])  # stop old container if any
        return _sh(["docker", "run", "-d", "--name", container,
                    "--restart", "unless-stopped", "-p", "8081:8080", image])

    @staticmethod
    def _health_check(url: str, attempts: int = 5,
                      delay: float = 3.0) -> dict[str, Any]:
        if not url:
            return {"ok": True, "detail": "no health endpoint configured"}
        for attempt in range(1, attempts + 1):
            try:
                r = httpx.get(url, timeout=5.0)
                if r.status_code < 500:
                    return {"ok": True, "attempt": attempt,
                            "status": r.status_code}
            except httpx.HTTPError:
                pass
            time.sleep(delay)
        return {"ok": False, "detail": f"unhealthy after {attempts} attempts"}

    @staticmethod
    def _rollback(container: str, previous_image: str) -> dict[str, Any]:
        _sh(["docker", "rm", "-f", container])
        res = _sh(["docker", "run", "-d", "--name", container,
                   "--restart", "unless-stopped", "-p", "8081:8080",
                   previous_image])
        return {"rolled_back_to": previous_image, **res}

    # ----------------------------------------------------------------- run
    def run(self, payload: dict[str, Any]) -> dict[str, Any]:
        """
        payload = {
          "action": "deploy" | "rollback",
          "repo": "owner/name", "version": "v1.3.0",
          "workdir": "/path/to/checkout",       # for docker build
          "health_url": "http://host:8081/health",
          "previous_image": "registry/user/app:v1.2.0"  # for rollback
        }
        """
        action = payload.get("action", "deploy")
        repo = payload.get("repo", "app")
        container = repo.replace("/", "-").lower()

        if action == "rollback":
            prev = payload.get("previous_image", "")
            if not prev:
                return {"agent": self.name, "status": "failed",
                        "error": "no previous image supplied"}
            res = self._rollback(container, prev)
            return {"agent": self.name, "status": "rolled_back" if res["ok"]
                    else "rollback_failed", "detail": res}

        version = payload.get("version") or time.strftime("v%Y%m%d-%H%M%S")
        image = payload.get("image") or self._image_tag(repo, version)
        steps: dict[str, Any] = {"image": image, "version": version}

        steps["login"] = self._registry_login()
        steps["build"] = self._build(payload.get("workdir", "."), image)
        if not steps["build"]["ok"]:
            return {"agent": self.name, "status": "build_failed",
                    "steps": steps}

        steps["push"] = self._push(image)
        if not steps["push"]["ok"]:
            return {"agent": self.name, "status": "push_failed",
                    "steps": steps}

        steps["deploy"] = self._deploy(image, container)
        if not steps["deploy"]["ok"]:
            return {"agent": self.name, "status": "deploy_failed",
                    "steps": steps}

        steps["health"] = self._health_check(payload.get("health_url", ""))
        if not steps["health"]["ok"]:
            prev = payload.get("previous_image", "")
            steps["rollback"] = (self._rollback(container, prev)
                                 if prev else {"ok": False,
                                               "detail": "no previous image"})
            return {"agent": self.name,
                    "status": "rolled_back" if steps["rollback"].get("ok")
                    else "unhealthy",
                    "steps": steps}

        return {"agent": self.name, "status": "deployed", "steps": steps}


def _sh_login() -> dict[str, Any]:
    """docker login passing the token over stdin."""
    try:
        proc = subprocess.run(
            ["docker", "login", settings.docker_registry,
             "-u", settings.docker_registry_user, "--password-stdin"],
            input=settings.docker_registry_token,
            capture_output=True, text=True, timeout=60)
        return {"ok": proc.returncode == 0, "stdout": proc.stdout[-2000:],
                "stderr": proc.stderr[-2000:], "code": proc.returncode}
    except (subprocess.TimeoutExpired, FileNotFoundError) as exc:
        return {"ok": False, "stdout": "", "stderr": str(exc), "code": -1}
