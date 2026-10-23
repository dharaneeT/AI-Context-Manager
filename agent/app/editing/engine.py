"""Applies edits to the workspace: safely, all-or-nothing, with a rollback point after every step."""
from __future__ import annotations

import difflib
import os
from pathlib import Path

from app.context.files import is_ignored
from app.core.errors import AgentError
from app.editing.parser import Edit
from app.editing.syntax import check_syntax
from app.sandbox import workspace as ws

MAX_EDIT_CHARS = 40_000


class EditError(AgentError):
    """One or more edits were refused. Nothing was changed. `problems` says exactly why (it is shown to the model)."""

    def __init__(self, problems: list):
        super().__init__("Edit refused: " + "; ".join(problems[:4]), 422)
        self.problems = problems


def normalize_path(raw: str) -> str:
    return raw.strip().replace("\\", "/").removeprefix("./")


def _check_path(path: str, allowed: set | None) -> str:
    if not path or path.startswith("/") or ":" in path.split("/")[0] or ".." in path.split("/"):
        return f"'{path}' is not a relative path inside the project"
    if path.startswith(".git/") or is_ignored(path):
        return f"'{path}' is in a protected location (secrets, build output, git)"
    if allowed is not None and path not in allowed:
        return f"'{path}' is not part of the approved plan"
    return ""


def _indent(line: str) -> str:
    return line[: len(line) - len(line.lstrip())]


def _closest(lines: list, search_lines: list) -> str:
    probe = next((l.strip() for l in search_lines if l.strip()), "")
    hits = difflib.get_close_matches(probe, [l.strip() for l in lines if l.strip()], n=1, cutoff=0.5)
    return f" The closest line in the file is: {hits[0][:100]!r}" if hits else ""


def find_and_replace(text: str, search: str, replace: str) -> str:
    """Replace `search` with `replace` in `text` (both LF-normalised). Exact match first, then a match that ignores
    indentation and trailing spaces (models often get indentation slightly wrong). Must match exactly ONE place."""
    count = text.count(search)
    if count == 1:
        return text.replace(search, replace, 1)
    if count > 1:
        raise ValueError(f"the SEARCH text matches {count} places; include more surrounding lines to make it unique")
    lines, s_lines = text.split("\n"), search.split("\n")
    while s_lines and not s_lines[-1].strip():
        s_lines.pop()
    while s_lines and not s_lines[0].strip():
        s_lines.pop(0)
    if not s_lines:
        raise ValueError("the SEARCH text is empty")
    wanted = [l.strip() for l in s_lines]
    hits = [i for i in range(len(lines) - len(wanted) + 1)
            if [l.strip() for l in lines[i:i + len(wanted)]] == wanted]
    if not hits:
        raise ValueError("the SEARCH text was not found in the file." + _closest(lines, s_lines))
    if len(hits) > 1:
        raise ValueError(f"the SEARCH text matches {len(hits)} places (ignoring indentation); add more lines")
    at = hits[0]
    target, source = _indent(lines[at]), _indent(s_lines[0])
    # keep the replacement's own relative indentation, but anchor it where the matched lines actually are
    new = [(target + r[len(source):] if r.startswith(source) else r) if r.strip() else r
           for r in (replace.split("\n") if replace != "" else [])]
    return "\n".join(lines[:at] + new + lines[at + len(wanted):])


def _counts(before: str, after: str) -> tuple:
    added = removed = 0
    for tag, a0, a1, b0, b1 in difflib.SequenceMatcher(None, before.split("\n"), after.split("\n"), autojunk=False).get_opcodes():
        if tag in ("replace", "delete"):
            removed += a1 - a0
        if tag in ("replace", "insert"):
            added += b1 - b0
    return added, removed


def _write(path: Path, text: str, crlf: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    data = text.replace("\n", "\r\n") if crlf else text
    tmp = path.with_name(path.name + ".agent-tmp")
    tmp.write_bytes(data.encode("utf-8"))
    os.replace(tmp, path)  # the file is either the old one or the new one, never half written


def apply_edits(tree: Path, edits: list, allowed: set | None, max_file_bytes: int) -> list:
    """Apply every edit or none. Returns one report per file: path, kind, lines added/removed, warnings."""
    problems = []
    originals: dict = {}  # path -> original text or None (file did not exist); restored on any failure
    working: dict = {}
    for e in edits:
        e.path = normalize_path(e.path)
        bad = _check_path(e.path, allowed)
        if bad:
            problems.append(bad)
            continue
        if len(e.search) + len(e.replace) > MAX_EDIT_CHARS:
            problems.append(f"{e.path}: the edit is too large ({MAX_EDIT_CHARS} characters at most); split it")
            continue
        target = tree / e.path
        if e.path not in working:
            if target.is_file():
                raw = target.read_bytes().decode("utf-8", errors="replace")
                originals[e.path] = raw
                working[e.path] = {"text": raw.replace("\r\n", "\n"), "crlf": "\r\n" in raw}
            else:
                originals[e.path] = None
                working[e.path] = {"text": None, "crlf": False}
        state = working[e.path]
        if e.kind == "create":
            if state["text"] is not None:
                problems.append(f"{e.path} already exists; edit it with a SEARCH block instead of creating it")
            else:
                state["text"] = e.replace.rstrip("\n") + "\n"
        else:
            if state["text"] is None:
                problems.append(f"{e.path} does not exist, so there is nothing to search in")
                continue
            try:
                state["text"] = find_and_replace(state["text"], e.search, e.replace)
            except ValueError as err:
                problems.append(f"{e.path}: {err}")
    reports = []
    if not problems:
        for path, state in working.items():
            if len(state["text"].encode("utf-8")) > max_file_bytes:
                problems.append(f"{path} would grow beyond {max_file_bytes} bytes")
                continue
            check = check_syntax(path, state["text"])
            if check["hard"]:
                problems.append(f"{path} would have a syntax error: {check['problems'][0]}")
            reports.append({"path": path, "kind": "create" if originals[path] is None else "modify",
                            "warnings": [] if check["hard"] else check["problems"],
                            "added": 0, "removed": 0})
    if problems:
        raise EditError(problems)
    for report in reports:
        path = report["path"]
        before = (originals[path] or "").replace("\r\n", "\n")
        _write(tree / path, working[path]["text"], working[path]["crlf"])
        report["added"], report["removed"] = _counts(before, working[path]["text"])
    return reports


def delete_files(tree: Path, paths: list, allowed: set | None) -> list:
    """Delete approved files. (The model never deletes: this runs only for plan steps whose action is 'delete'.)"""
    problems = []
    for p in paths:
        p = normalize_path(p)
        bad = _check_path(p, allowed)
        if bad:
            problems.append(bad)
        elif not (tree / p).is_file():
            problems.append(f"{p} does not exist")
    if problems:
        raise EditError(problems)
    out = []
    for p in paths:
        p = normalize_path(p)
        lines = len((tree / p).read_text(encoding="utf-8", errors="replace").splitlines())
        (tree / p).unlink()
        out.append({"path": p, "kind": "delete", "warnings": [], "added": 0, "removed": lines})
    return out


def apply_step(tree: Path, edits: list, allowed: set, max_file_bytes: int, label: str) -> dict:
    """Apply edits, then save a checkpoint. On any problem nothing changes (EditError)."""
    reports = apply_edits(tree, edits, allowed, max_file_bytes)
    sha = ws.checkpoint(tree, label)
    return {"checkpoint": sha, "label": label, "files": reports}