"""Get validated JSON out of small models: tolerant parsing, validation, and a bounded repair loop."""
from __future__ import annotations

import json
import re
from typing import Callable

from app.core.errors import AgentError
from app.llm.base import ChatMessage, LLMClient, UsageTracker

_FENCE = re.compile(r"^```[a-zA-Z]*\s*|\s*```$", re.MULTILINE)


class StructuredOutputError(AgentError):
    def __init__(self, message: str):
        super().__init__(message, 502)


def extract_json(text: str):
    """Parse JSON even when the model wraps it in ```fences``` or adds chatter before/after it."""
    cleaned = _FENCE.sub("", text.strip()).strip()
    try:
        return json.loads(cleaned)
    except json.JSONDecodeError:
        pass
    decoder = json.JSONDecoder()
    for match in re.finditer(r"[\{\[]", cleaned):
        try:
            value, _ = decoder.raw_decode(cleaned[match.start():])
            return value
        except json.JSONDecodeError:
            continue
    raise ValueError("no JSON found in the model reply")


def generate_json(client: LLMClient, messages: list, validate: Callable[[object], list] | None = None, *,
                  max_attempts: int = 3, usage: UsageTracker | None = None, purpose: str = "",
                  max_output_tokens: int | None = None):
    """Ask for JSON; if parsing or validation fails, show the model its mistake and retry (bounded)."""
    convo = list(messages)
    last_problem = "unknown"
    for attempt in range(1, max_attempts + 1):
        result = client.chat(convo, json_mode=True, max_output_tokens=max_output_tokens)
        if usage is not None:
            usage.add(result, f"{purpose} (attempt {attempt})")
        try:
            data = extract_json(result.text)
            problems = validate(data) if validate else []
        except ValueError as e:
            data, problems = None, [str(e)]
        if not problems:
            return data
        last_problem = "; ".join(problems)
        convo += [ChatMessage("assistant", result.text),
                  ChatMessage("user", f"That reply was not acceptable: {last_problem}. "
                                      "Reply again with ONLY the corrected JSON object.")]
    raise StructuredOutputError(f"The model did not return valid JSON after {max_attempts} attempts: {last_problem}")