"""What the model sees for ONE plan step. Small on purpose: a 4,096-token window cannot hold your project, so each
step gets only its own files (whole if they fit, windows around the relevant lines if not) plus short references."""
from __future__ import annotations

import re
from pathlib import Path

from app.context.profile import conventions_text
from app.llm.base import estimate_tokens
from app.llm.prompts import render

_SIGNATURE = re.compile(r"^\s*(?:@\w+.*|(?:public|private|protected|static|export|default|async|function|class|interface|"
                        r"record|enum|const|def)\b.*)$")
MARGIN = 20


def outline(text: str, limit: int = 40) -> str:
    """Declarations only: enough for a model to call code it cannot see in full."""
    lines = [l.rstrip() for l in text.split("\n") if _SIGNATURE.match(l) and len(l) < 200]
    return "\n".join(lines[:limit])



def _fit_file(text: str, ranges: list, budget_tokens: int) -> tuple:
    """(shown_text, note). Whole file if it fits; otherwise windows around the matched ranges; otherwise head + outline."""
    if estimate_tokens(text) <= budget_tokens:
        return text, ""
    lines = text.split("\n")
    if ranges:
        spans = []
        for a, b in sorted((max(1, a - MARGIN), min(len(lines), b + MARGIN)) for a, b in ranges):
            if spans and a <= spans[-1][1] + 1:
                spans[-1] = (spans[-1][0], max(spans[-1][1], b))
            else:
                spans.append((a, b))
        shown = "\n// ...\n".join("\n".join(lines[a - 1:b]) for a, b in spans)
        if estimate_tokens(shown) <= budget_tokens:
            return shown, f"(partial: only the parts around lines {', '.join(f'{a}-{b}' for a, b in spans)} of {len(lines)})"
    head = "\n".join(lines[:60])
    shown = head + "\n// ... (file continues) ...\n" + outline("\n".join(lines[60:]), 30)
    return shown[: budget_tokens * 4], f"(partial: first 60 of {len(lines)} lines, then declarations only)"


def _style_example(tree: Path, path: str, all_siblings: list) -> str:
    """For a NEW file: show a short existing file from the same folder so the model copies the style."""
    folder = path.rsplit("/", 1)[0] if "/" in path else ""
    for sibling in all_siblings:
        if sibling.rsplit("/", 1)[0] == folder and sibling != path and (tree / sibling).is_file():
            return "\n".join((tree / sibling).read_text(encoding="utf-8", errors="replace").split("\n")[:50])
    return ""


def build_step_prompt(settings, run: dict, step: dict, tree: Path, earlier_files: list, all_files: list):
    understanding = run.get("understanding") or {}
    profile = run.get("profile") or {}
    budget = settings.prompt_budget_tokens
    ranges: dict = {}
    for c in run.get("candidates") or []:
        ranges[c["path"]] = [tuple(r) for r in c.get("ranges", [])]
    per_file = max(300, int(budget * 0.45) // max(1, len(step["files"])))
    parts = []
    for path in step["files"]:
        target = tree / path
        if target.is_file():
            shown, note = _fit_file(target.read_text(encoding="utf-8", errors="replace").replace("\r\n", "\n"),
                                    ranges.get(path, []), per_file)
            parts.append(f"=== {path} {note}\n{shown}")
        else:
            example = _style_example(tree, path, all_files)
            parts.append(f"=== {path} (new file: it does not exist yet)" +
                         (f"\nStyle example from the same folder (copy the style, not the logic):\n{example}" if example else ""))
    related = []
    for path in earlier_files[-4:]:
        if path not in step["files"] and (tree / path).is_file():
            related.append(f"=== {path}\n{outline((tree / path).read_text(encoding='utf-8', errors='replace'))}")
    indent = ", ".join(f"{k}: {v}" for k, v in (profile.get("indent") or {}).items()) or "match the existing files"
    prompt = render(
        "implement_step",
        summary=understanding.get("summary") or run["command"],
        criteria="\n".join(f"- {c}" for c in (understanding.get("acceptance_criteria") or [])[:5]) or "(none)",
        title=step["title"], description=step["description"] or step["title"],
        conventions=conventions_text(profile, 500) or "(unknown)",
        files="\n\n".join(parts) or "(none)", related="\n\n".join(related)[: int(budget * 0.15) * 4] or "(none)",
        indent=indent)
    return prompt
