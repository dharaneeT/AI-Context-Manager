"""Port of the Spring SecretRedactor: mask secrets before any text leaves a file reader. Errs on over-redacting."""
from __future__ import annotations

import re

_KEYWORDS = r"password|passwd|pwd|secret|api[_-]?key|access[_-]?key|auth[_-]?token|private[_-]?key|client[_-]?secret"

_TOKENS = [
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bsk-[A-Za-z0-9_\-]{20,}"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"),
]
_QUOTED = re.compile(r"(?i)(\b[\w.-]*(?:" + _KEYWORDS + r")[\w.-]*\s*[=:]\s*)([\"'])([^\"'\s]{4,})\2")
_LINE = re.compile(r"(?im)^([ \t]*[\w.-]*(?:" + _KEYWORDS + r")[\w.-]*[ \t]*[=:][ \t]*)([^\s#\"'][^\r\n#]*)$")


def redact(text: str) -> tuple:
    """Returns (clean_text, number_of_redactions)."""
    if not text:
        return text, 0
    count = 0
    out = text
    for pattern in _TOKENS:
        out, n = pattern.subn("[REDACTED]", out)
        count += n
    out, n = _QUOTED.subn(r"\1\2[REDACTED]\2", out)
    count += n
    out, n = _LINE.subn(r"\1[REDACTED]", out)
    count += n
    return out, count