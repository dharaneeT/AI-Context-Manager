from __future__ import annotations

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.base import LLMClient
from app.llm.mock import MockLLM
from app.llm.ollama import OllamaClient
from app.llm.openai_compat import OpenAICompatClient


def create_llm(settings: Settings) -> LLMClient:
    provider = settings.llm_provider
    if provider == "ollama":
        return OllamaClient(settings)
    if provider in ("openai_compat", "openai"):
        return OpenAICompatClient(settings)
    if provider == "mock":
        return MockLLM()
    raise AgentError(f"Unknown LLM_PROVIDER '{provider}'. Use ollama, openai_compat or mock.", 400)