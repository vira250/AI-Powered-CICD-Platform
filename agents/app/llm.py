"""LLM client — Gemini model accessed through Google AI's OpenAI-compatible API.

Supports primary and fallback Gemini API keys and models with automatic quota failover.
"""
import logging

from openai import OpenAI, APIError, APIConnectionError, APITimeoutError

from .config import settings

log = logging.getLogger(__name__)


class LLMClient:
    def __init__(self) -> None:
        self._primary_client = OpenAI(
            api_key=settings.gemini_api_key or "missing-key",
            base_url=settings.llm_base_url,
        )
        self.model = settings.llm_model

        self._fallback_client = None
        if settings.fallback_gemini_api_key:
            self._fallback_client = OpenAI(
                api_key=settings.fallback_gemini_api_key,
                base_url=settings.fallback_llm_base_url,
            )
        self.fallback_model = settings.fallback_llm_model or "gemini-3.5-flash"

    def chat_with_usage(self, system: str, user: str, temperature: float = 0.2,
                        max_tokens: int = 4096) -> tuple[str, dict]:
        """Single-turn chat completion with automatic key and model fallback on 429/quota."""
        # 1. Prepare candidates: (client_name, client_instance, model_name)
        attempts = []
        
        # Primary attempts
        for m in list(dict.fromkeys([self.model, "gemini-3.5-flash", "gemini-3.6-flash"])):
            attempts.append(("Primary", self._primary_client, m))

        # Fallback key attempts (if configured)
        if self._fallback_client:
            for m in list(dict.fromkeys([self.fallback_model, "gemini-3.5-flash", "gemini-3.6-flash"])):
                attempts.append(("Fallback", self._fallback_client, m))

        for tier, client, m in attempts:
            try:
                resp = client.chat.completions.create(
                    model=m,
                    temperature=temperature,
                    max_tokens=max_tokens,
                    messages=[
                        {"role": "system", "content": system},
                        {"role": "user", "content": user},
                    ],
                )
                text = resp.choices[0].message.content or ""
                usage = {
                    "prompt_tokens": resp.usage.prompt_tokens if resp.usage else 0,
                    "completion_tokens": resp.usage.completion_tokens if resp.usage else 0,
                    "total_tokens": resp.usage.total_tokens if resp.usage else 0,
                }
                return text, usage
            except APITimeoutError:
                log.error("[%s] LLM request timed out (model=%s)", tier, m)
            except APIConnectionError as exc:
                log.error("[%s] Cannot reach LLM endpoint: %s", tier, exc)
            except APIError as exc:
                log.warn("[%s] LLM API error with model %s (status=%s): %s — trying next fallback if available",
                         tier, m, exc.status_code, exc)
                continue

        return "", {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}

    def chat(self, system: str, user: str, temperature: float = 0.2,
             max_tokens: int = 4096) -> str:
        """Single-turn chat completion. Returns the assistant message text."""
        text, _ = self.chat_with_usage(system, user, temperature, max_tokens)
        return text

    def available(self) -> bool:
        return bool(settings.gemini_api_key or settings.fallback_gemini_api_key)


llm = LLMClient()
