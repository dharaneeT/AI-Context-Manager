"""Any hosted OpenAI-style API (OpenAI, Groq, OpenRouter, Together, ...). Optional: use when the 8 GB PC is too slow."""
from __future__ import annotations

import time

from app.core.config import Settings
from app.core.errors import LlmUnavailable
from app.core.http import ConnectionFailure, HttpFailure, request_json
from app.llm.base import LLMClient, LLMResult, estimate_tokens


class OpenAICompatClient(LLMClient):
    name = "openai_compat"

    def __init__(self, settings: Settings):
        self.s = settings

    def _headers(self) -> dict:
        return {"Authorization": f"Bearer {self.s.openai_api_key}"}

    def chat(self, messages, *, json_mode=False, max_output_tokens=None, temperature=0.1) -> LLMResult:
        if not (self.s.openai_base_url and self.s.openai_model):
            raise LlmUnavailable("Set OPENAI_BASE_URL and OPENAI_MODEL (and OPENAI_API_KEY) in agent/.env")
        body = {
            "model": self.s.openai_model,
            "messages": [{"role": m.role, "content": m.content} for m in messages],
            "max_tokens": max_output_tokens or self.s.llm_max_output_tokens,
            "temperature": temperature,
        }
        if json_mode:
            body["response_format"] = {"type": "json_object"}
        started = time.monotonic()
        try:
            data = request_json("POST", f"{self.s.openai_base_url}/chat/completions", body=body,
                                headers=self._headers(), timeout=self.s.llm_timeout_seconds)
        except ConnectionFailure as e:
            raise LlmUnavailable(str(e)) from e
        except HttpFailure as e:
            raise LlmUnavailable(f"LLM API error: {e}") from e
        text = data["choices"][0]["message"]["content"] or ""
        usage = data.get("usage") or {}
        return LLMResult(
            text=text,
            model=self.s.openai_model,
            input_tokens=int(usage.get("prompt_tokens") or estimate_tokens("".join(m.content for m in messages))),
            output_tokens=int(usage.get("completion_tokens") or estimate_tokens(text)),
            seconds=time.monotonic() - started,
        )

    def status(self) -> dict:
        configured = bool(self.s.openai_base_url and self.s.openai_model and self.s.openai_api_key)
        return {"provider": self.name, "model": self.s.openai_model, "reachable": configured,
                "model_available": configured,
                "detail": "" if configured else "OPENAI_BASE_URL, OPENAI_MODEL and OPENAI_API_KEY are required"}