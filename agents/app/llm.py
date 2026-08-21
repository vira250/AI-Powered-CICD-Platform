"""LLM client — Gemini model accessed through Google AI's OpenAI-compatible API.

Every agent uses this as its "brain". The model id is configurable via the
LLM_MODEL environment variable so you can swap Gemini variants without code
changes.
"""
import logging

from openai import OpenAI, APIError, APIConnectionError, APITimeoutError

from .config import settings

log = logging.getLogger(__name__)


class LLMClient:
    def __init__(self) -> None:
        self._client = OpenAI(
            api_key=settings.gemini_api_key or "missing-key",
            base_url=settings.llm_base_url,
        )
        self.model = settings.llm_model

    def chat_with_usage(self, system: str, user: str, temperature: float = 0.2,
                        max_tokens: int = 4096) -> tuple[str, dict]:
        """Single-turn chat completion with automatic model fallback on 429/quota."""
        models_to_try = [self.model, "gemini-3.6-flash", "gemini-3.6-pro"]
        # deduplicate while keeping order
        models = list(dict.fromkeys(models_to_try))

        for m in models:
            try:
                resp = self._client.chat.completions.create(
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
                log.error("LLM request timed out (model=%s)", m)
            except APIConnectionError as exc:
                log.error("Cannot reach LLM endpoint %s: %s", settings.llm_base_url, exc)
                break
            except APIError as exc:
                log.warn("LLM API error with model %s (status=%s): %s — trying next model if available", m, exc.status_code, exc)
                continue

        return "", {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}

    def chat(self, system: str, user: str, temperature: float = 0.2,
             max_tokens: int = 4096) -> str:
        """Single-turn chat completion. Returns the assistant message text."""
        text, _ = self.chat_with_usage(system, user, temperature, max_tokens)
        return text

    def available(self) -> bool:
        return bool(settings.gemini_api_key)


llm = LLMClient()

