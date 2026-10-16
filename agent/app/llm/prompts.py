"""Versioned prompt templates. Files live in app/llm/prompts/<name>.v<N>.md and use $placeholders."""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from string import Template

from app.core.errors import AgentError

PROMPT_DIR = Path(__file__).parent / "prompts"
_NAME = re.compile(r"^(?P<name>[a-z_]+)\.v(?P<version>\d+)\.md$")


@dataclass(frozen=True)
class Prompt:
    name: str
    version: int
    text: str


def render(name: str, version: int | None = None, **variables) -> Prompt:
    """Latest version of a prompt unless a version is requested. Missing variables are left visible, not hidden."""
    found = {}
    for path in PROMPT_DIR.glob(f"{name}.v*.md"):
        m = _NAME.match(path.name)
        if m and m.group("name") == name:
            found[int(m.group("version"))] = path
    if not found:
        raise AgentError(f"Prompt '{name}' not found in {PROMPT_DIR}", 500)
    chosen = version if version is not None else max(found)
    if chosen not in found:
        raise AgentError(f"Prompt '{name}' has no version {chosen}", 500)
    text = Template(found[chosen].read_text(encoding="utf-8")).safe_substitute(**variables)
    return Prompt(name, chosen, text.strip())