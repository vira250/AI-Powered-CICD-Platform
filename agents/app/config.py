"""Central configuration for the AI agents service."""
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=[".env", "../.env"], extra="ignore")

    # LLM (Gemini via Google AI's OpenAI-compatible endpoint)
    gemini_api_key: str = ""
    llm_model: str = "gemini-3.6-flash"
    llm_base_url: str = "https://generativelanguage.googleapis.com/v1beta/openai/"

    # Deployment agent
    docker_registry: str = "docker.io"
    docker_registry_user: str = ""
    docker_registry_token: str = ""
    production_host: str = ""

    agents_port: int = 8001


settings = Settings()

