"""Centralized configuration for the AI Agents service."""

import os
from pathlib import Path
from dotenv import load_dotenv

env_path = Path(__file__).resolve().parent / ".env"
load_dotenv(dotenv_path=env_path)

# LLM Configuration
GOOGLE_API_KEY = os.getenv("GOOGLE_API_KEY", "")
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
ANTHROPIC_API_KEY = os.getenv("ANTHROPIC_API_KEY", "")

LLM_MODEL = os.getenv("LLM_MODEL", "gemini-3.5-flash")
LLM_TEMPERATURE = float(os.getenv("LLM_TEMPERATURE", "0.3"))

# Backend Configuration
BACKEND_URL = os.getenv("BACKEND_URL", "http://localhost:8080")

# Agent Limits
MAX_FILES_FOR_REVIEW = int(os.getenv("REVIEW_MAX_FILES", "50"))
MAX_CHARS_FOR_PROMPT = int(os.getenv("REVIEW_MAX_CHARS", "120000"))
MAX_FILES_FOR_PIPELINE = int(os.getenv("PIPELINE_MAX_FILES", "80"))


def get_llm_provider() -> str:
    """Determine which LLM provider to use based on available API keys."""
    if GOOGLE_API_KEY:
        return "google"
    if OPENAI_API_KEY:
        return "openai"
    if ANTHROPIC_API_KEY:
        return "anthropic"
    return "google"  # default
