
"""Deterministic task understanding; model-based analysis comes later."""
from __future__ import annotations

import re
from dataclasses import dataclass, field

_AREAS = {
    "frontend": (
        r"\b(frontend|front-end|ui|react|component|page|screen|"
        r"button|form|css|style|jsx|tsx|view)\b"
    ),
    "backend": (
        r"\b(backend|back-end|api|endpoint|controller|service|"
        r"spring|rest|java|http|request|response)\b"
    ),
    "database": (
        r"\b(database|db|table|column|entity|migration|sql|"
        r"postgres|repository|schema|jpa)\b"
    ),
    "tests": r"\b(test|tests|testing|junit|coverage|assert|spec)\b",
    "config": (
        r"\b(config|configuration|setting|settings|properties|"
        r"dependency|dependencies|build|docker|port|env)\b"
    ),
}

_STOP = set(
    "a an the to of in on for and or with that this it is are be "
    "add make create build new should can will from into by as at "
    "we you i my our their its use using when then so not no yes".split()
)


@dataclass
class Understanding:
    command: str
    areas: list = field(default_factory=list)
    keywords: list = field(default_factory=list)
    llm_used: bool = False

    def as_dict(self) -> dict:
        return {
            "command": self.command,
            "areas": self.areas,
            "keywords": self.keywords,
            "llm_used": self.llm_used,
        }


def understand(command: str) -> Understanding:
    text = command.lower()
    areas = [
        name for name, pattern in _AREAS.items()
        if re.search(pattern, text)
    ]

    words = re.findall(r"[a-z][a-z0-9_]{2,}", text)
    keywords = []

    for word in words:
        if word not in _STOP and word not in keywords:
            keywords.append(word)

    return Understanding(
        command.strip(),
        areas,
        keywords[:12],
    )
