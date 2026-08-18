"""LLM client — Gemini model accessed through Google AI's OpenAI-compatible API.

Every agent uses this as its "brain". The model id is configurable via the
LLM_MODEL environment variable so you can swap Gemini variants without code
changes.
"""
from openai import OpenAI

from .config import settings


class LLMClient:
    def __init__(self) -> None:
        self._client = OpenAI(
            api_key=settings.openrouter_api_key or "missing-key",
            base_url=settings.llm_base_url,
        )
        self.model = settings.llm_model

    def chat(self, system: str, user: str, temperature: float = 0.2,
             max_tokens: int = 4096) -> str:
        """Single-turn chat completion. Returns the assistant message text."""
        resp = self._client.chat.completions.create(
            model=self.model,
            temperature=temperature,
            max_tokens=max_tokens,
            messages=[
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        )
        return resp.choices[0].message.content or ""

    def available(self) -> bool:
        return bool(settings.openrouter_api_key)


llm = LLMClient()
