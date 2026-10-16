"""The one interface the rest of the agent talks to. Providers (Ollama, OpenAI-style, mock) implement it."""
from __future__ import annotations

import math
from abc import ABC, abstractmethod
from dataclasses import dataclass, field

from app.core.errors import AgentError


def estimate_tokens(text: str) -> int:
    """Cheap token estimate (about 4 characters per token). Good enough for budgets; exact counts come from the model."""
    return max(1, math.ceil(len(text) / 4)) if text else 0


@dataclass(frozen=True)
class ChatMessage:
    role: str  # "system" | "user" | "assistant"
    content: str


@dataclass
class LLMResult:
    text: str
    model: str
    input_tokens: int
    output_tokens: int
    seconds: float


class BudgetExceeded(AgentError):
    def __init__(self, used: int, limit: int):
        super().__init__(f"LLM token budget exceeded ({used} > {limit})", 409)


@dataclass
class UsageTracker:
    """Adds up tokens and time across calls of one run, and stops the run when the budget is gone."""

    limit_tokens: int = 50_000
    input_tokens: int = 0
    output_tokens: int = 0
    seconds: float = 0.0
    calls: int = 0
    log: list = field(default_factory=list)

    def add(self, result: LLMResult, purpose: str = "") -> None:
        self.input_tokens += result.input_tokens
        self.output_tokens += result.output_tokens
        self.seconds += result.seconds
        self.calls += 1
        self.log.append({"purpose": purpose, "model": result.model, "in": result.input_tokens,
                         "out": result.output_tokens, "seconds": round(result.seconds, 2)})
        if self.total > self.limit_tokens:
            raise BudgetExceeded(self.total, self.limit_tokens)

    @property
    def total(self) -> int:
        return self.input_tokens + self.output_tokens

    def summary(self) -> dict:
        return {"calls": self.calls, "input_tokens": self.input_tokens, "output_tokens": self.output_tokens,
                "seconds": round(self.seconds, 2), "limit_tokens": self.limit_tokens}


class LLMClient(ABC):
    name: str = "base"

    @abstractmethod
    def chat(self, messages: list, *, json_mode: bool = False, max_output_tokens: int | None = None,
             temperature: float = 0.1) -> LLMResult: ...

    @abstractmethod
    def status(self) -> dict:
        """Cheap health check: {"provider", "model", "reachable", "model_available", "detail"}."""