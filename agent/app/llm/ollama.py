"""Local Ollama. Settings are chosen for an 8 GB, CPU-only PC: small context, short keep-alive, capped output."""
from __future__ import annotations

import time

from app.core.config import Settings
from app.core.errors import LlmUnavailable
from app.core.http import ConnectionFailure, HttpFailure, request_json
from app.llm.base import ChatMessage, LLMClient, LLMResult, estimate_tokens


class OllamaClient(LLMClient):
    name = "ollama"

    def __init__(self, settings: Settings):
        self.s = settings

    def chat(self, messages, *, json_mode=False, max_output_tokens=None, temperature=0.1) -> LLMResult:
        body = {
            "model": self.s.ollama_model,
            "messages": [{"role": m.role, "content": m.content} for m in messages],
            "stream": False,
            "keep_alive": self.s.llm_keep_alive,  # unload the model soon after use to give RAM back
            "options": {
                "num_ctx": self.s.llm_num_ctx,
                "num_predict": max_output_tokens or self.s.llm_max_output_tokens,
                "temperature": temperature,
            },
        }
        if json_mode:
            body["format"] = "json"
        started = time.monotonic()
        try:
            data = request_json("POST", f"{self.s.ollama_url}/api/chat", body=body,
                                timeout=self.s.llm_timeout_seconds)
        except ConnectionFailure as e:
            raise LlmUnavailable(f"Ollama is not running at {self.s.ollama_url}. Start it, then run: "
                                 f"ollama pull {self.s.ollama_model}") from e
        except HttpFailure as e:
            if e.status == 404:
                raise LlmUnavailable(f"Model '{self.s.ollama_model}' is not installed. Run: "
                                     f"ollama pull {self.s.ollama_model}") from e
            raise LlmUnavailable(f"Ollama error: {e}") from e
        text = ((data or {}).get("message") or {}).get("content", "")
        prompt_text = "".join(m.content for m in messages)
        return LLMResult(
            text=text,
            model=self.s.ollama_model,
            input_tokens=int(data.get("prompt_eval_count") or estimate_tokens(prompt_text)),
            output_tokens=int(data.get("eval_count") or estimate_tokens(text)),
            seconds=time.monotonic() - started,
        )

    def status(self) -> dict:
        info = {"provider": self.name, "model": self.s.ollama_model, "reachable": False,
                "model_available": False, "detail": ""}
        try:
            data = request_json("GET", f"{self.s.ollama_url}/api/tags", timeout=3)
        except (ConnectionFailure, HttpFailure) as e:
            info["detail"] = f"Ollama not reachable at {self.s.ollama_url}: {e}"
            return info
        names = [m.get("name", "") for m in (data or {}).get("models", [])]
        info["reachable"] = True
        info["model_available"] = any(n == self.s.ollama_model or n.split(":")[0] == self.s.ollama_model for n in names)
        info["installed_models"] = names
        if not info["model_available"]:
            info["detail"] = f"Run: ollama pull {self.s.ollama_model}"
        return info