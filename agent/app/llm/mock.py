"""A scripted model for tests: returns the queued replies in order. No network, no RAM."""
from __future__ import annotations

from app.llm.base import LLMClient, LLMResult, estimate_tokens


class MockLLM(LLMClient):
    name = "mock"

    def __init__(self, replies: list | None = None, model: str = "mock-1"):
        self.replies = list(replies or [])
        self.model = model
        self.calls: list = []

    def chat(self, messages, *, json_mode=False, max_output_tokens=None, temperature=0.1) -> LLMResult:
        self.calls.append(messages)
        text = self.replies.pop(0) if self.replies else "{}"
        return LLMResult(text, self.model, estimate_tokens("".join(m.content for m in messages)),
                         estimate_tokens(text), 0.0)

    def status(self) -> dict:
        return {"provider": self.name, "model": self.model, "reachable": True, "model_available": True, "detail": ""}