"""The edit format the model writes. Small models are bad at unified diffs (line numbers drift) but good at
copying text, so edits are SEARCH/REPLACE blocks:

    FILE: backend/src/main/java/com/demo/Foo.java
    <<<<<<< SEARCH
    exact lines that exist today
    =======
    the lines that replace them
    >>>>>>> REPLACE

An EMPTY search section creates a new file (the replace section is the whole file)."""
from __future__ import annotations

import re
from dataclasses import dataclass

SEARCH, DIVIDER, REPLACE = "<<<<<<< SEARCH", "=======", ">>>>>>> REPLACE"
_FILE = re.compile(r"^\s*(?:[#*`>\-\s]*)(?:FILE|File|file|PATH|Path)\s*:\s*(.+?)\s*[`*]*\s*$")


@dataclass
class Edit:
    path: str
    search: str
    replace: str

    @property
    def kind(self) -> str:
        return "create" if not self.search.strip() else "replace"


def _clean_path(raw: str) -> str:
    return raw.strip().strip("`*\"' ").replace("\\", "/").removeprefix("./")


def parse_edits(text: str) -> tuple:
    """Returns (edits, problems). Text outside blocks (explanations, code fences) is ignored."""
    edits, problems = [], []
    path, state, search, replace = None, "out", [], []
    for number, line in enumerate(text.replace("\r\n", "\n").split("\n"), 1):
        stripped = line.strip()
        if state == "out":
            m = _FILE.match(line)
            if m and not stripped.startswith(("<", "=")):
                path = _clean_path(m.group(1))
            elif stripped == SEARCH:
                if not path:
                    problems.append(f"line {number}: a SEARCH block came before any 'FILE: path' line")
                state, search, replace = "search", [], []
        elif state == "search":
            if stripped == DIVIDER:
                state = "replace"
            else:
                search.append(line)
        elif state == "replace":
            if stripped == REPLACE:
                if path:
                    edits.append(Edit(path, "\n".join(search), "\n".join(replace)))
                state = "out"
            elif stripped == SEARCH:
                problems.append(f"line {number}: a new SEARCH started before the previous block was closed with '{REPLACE}'")
                state, search, replace = "search", [], []
            else:
                replace.append(line)
    if state != "out":
        problems.append(f"the last block is not finished (it needs '{DIVIDER}' and '{REPLACE}')")
    if not edits and not problems:
        problems.append("no edit blocks were found; use FILE: path then a SEARCH/REPLACE block")
    return edits, problems