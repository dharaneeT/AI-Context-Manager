"""What the task means. Heuristics always run (fast, free); on Day 6 the LLM adds criteria and questions on top."""
from __future__ import annotations

import re
from dataclasses import dataclass, field

_AREAS = {
    "frontend": r"\b(frontend|front-end|ui|react|component|page|screen|button|form|css|style|jsx|tsx|view)\b",
    "backend": r"\b(backend|back-end|api|endpoint|controller|service|spring|rest|java|http|request|response)\b",
    "database": r"\b(database|db|table|column|entity|migration|sql|postgres|repository|schema|jpa)\b",
    "tests": r"\b(test|tests|testing|junit|coverage|assert|spec)\b",
    "config": r"\b(config|configuration|setting|settings|propert(?:y|ies)|dependency|dependencies|build|docker|port|env)\b",
}
VALID_AREAS = tuple(_AREAS)
_STOP = set("a an the to of in on for and or with that this it is are be add make create build new should can will "
            "from into by as at we you i my our their its use using when then so not no yes".split())


@dataclass
class Understanding:
    command: str
    areas: list = field(default_factory=list)
    keywords: list = field(default_factory=list)
    summary: str = ""
    acceptance_criteria: list = field(default_factory=list)
    constraints: list = field(default_factory=list)
    assumptions: list = field(default_factory=list)
    questions: list = field(default_factory=list)      # [{"id": "q1", "question": "...", "why": "..."}]
    answers: dict = field(default_factory=dict)        # {"q1": "the user's answer"}
    needs_clarification: bool = False
    llm_used: bool = False
    source: str = "heuristic"                          # "heuristic" or "llm"
    note: str = ""

    def as_dict(self) -> dict:
        return dict(self.__dict__)

    @classmethod
    def from_dict(cls, data: dict) -> "Understanding":
        known = {k: v for k, v in data.items() if k in cls.__dataclass_fields__}
        return cls(**known)

    def search_text(self) -> str:
        """The text sent to search: the command plus whatever the user clarified."""
        extra = " ".join(str(v) for v in self.answers.values() if v)
        return f"{self.command} {extra}".strip()


def understand(command: str) -> Understanding:
    text = command.lower()
    areas = [name for name, pattern in _AREAS.items() if re.search(pattern, text)]
    words = re.findall(r"[a-z][a-z0-9_]{2,}", text)
    keywords = []
    for w in words:
        if w not in _STOP and w not in keywords:
            keywords.append(w)
    return Understanding(command.strip(), areas, keywords[:12])