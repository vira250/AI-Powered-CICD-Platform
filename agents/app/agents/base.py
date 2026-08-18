"""Base class shared by all agents."""
from __future__ import annotations

from typing import Any

from ..llm import llm


class BaseAgent:
    name: str = "base"
    description: str = ""

    def __init__(self) -> None:
        self.llm = llm

    def run(self, payload: dict[str, Any]) -> dict[str, Any]:  # pragma: no cover
        raise NotImplementedError

    def info(self) -> dict[str, str]:
        return {"name": self.name, "description": self.description}
