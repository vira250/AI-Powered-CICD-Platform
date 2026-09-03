"""Central configuration for the AI agents service."""
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=[".env", "../.env"], extra="ignore")

    # LLM (Gemini via Google AI's OpenAI-compatible endpoint)
    gemini_api_key: str = ""
    llm_model: str = "gemini-3.5-flash"
    llm_base_url: str = "https://generativelanguage.googleapis.com/v1beta/openai/"

    # Fallback LLM (Optional second Gemini key/model for quota failover)
    fallback_gemini_api_key: str = ""
    fallback_llm_model: str = "gemini-3.5-flash"
    fallback_llm_base_url: str = "https://generativelanguage.googleapis.com/v1beta/openai/"

    agents_port: int = 8001


settings = Settings()
