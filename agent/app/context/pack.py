"""Build the context pack: COMPLETE files for the best candidates, windows for big ones, paths for the rest."""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from app.context.expander import Candidate
from app.context.files import FileContent, read_file
from app.llm.base import estimate_tokens

WINDOW_MARGIN = 12          # lines of context around each matched chunk
ITEM_OVERHEAD_TOKENS = 60    # header + fences per file
PATH_ONLY_TOKENS = 25
SAFETY = 0.90                # use 90% of the budget, like the backend's safety margin
MAX_FULL_SHARE = 0.35        # one file may take at most 35% of the budget when read in full


@dataclass
class PackItem:
    path: str
    level: str  # FULL | WINDOW | PATH_ONLY
    tokens: int
    score: float
    reasons: list
    language: str = ""
    line_count: int = 0
    redactions: int = 0
    truncated: bool = False
    text: str = ""

    def public(self) -> dict:
        return {"path": self.path, "level": self.level, "tokens": self.tokens, "score": round(self.score, 3),
                "reasons": self.reasons, "lineCount": self.line_count, "redactions": self.redactions,
                "truncated": self.truncated}


@dataclass
class Pack:
    command: str
    budget_tokens: int
    items: list = field(default_factory=list)
    not_included: list = field(default_factory=list)
    notes: list = field(default_factory=list)
    repo: dict = field(default_factory=dict)
    markdown: str = ""

    @property
    def pack_tokens(self) -> int:
        return estimate_tokens(self.markdown)

    def stats(self) -> dict:
        levels = {"FULL": 0, "WINDOW": 0, "PATH_ONLY": 0}
        for i in self.items:
            levels[i.level] += 1
        return {"budgetTokens": self.budget_tokens, "packTokens": self.pack_tokens,
                "withinBudget": self.pack_tokens <= self.budget_tokens, "fullFiles": levels["FULL"],
                "windowedFiles": levels["WINDOW"], "pathOnlyFiles": levels["PATH_ONLY"],
                "excludedFiles": len(self.not_included)}

    def public(self) -> dict:
        return {"command": self.command, "stats": self.stats(), "repo": self.repo,
                "items": [i.public() for i in self.items], "notIncluded": self.not_included, "notes": self.notes}


def _merge_windows(ranges: list, line_count: int) -> list:
    spans = sorted((max(1, a - WINDOW_MARGIN), min(line_count, b + WINDOW_MARGIN)) for a, b in ranges)
    merged: list = []
    for a, b in spans:
        if merged and a <= merged[-1][1] + 1:
            merged[-1] = (merged[-1][0], max(merged[-1][1], b))
        else:
            merged.append((a, b))
    return merged


def _window_text(content: FileContent, ranges: list) -> str:
    lines = content.text.split("\n")
    parts = []
    for a, b in _merge_windows(ranges, len(lines)):
        parts.append(f"// ... lines {a}-{b} of {content.line_count} ...\n" + "\n".join(lines[a - 1:b]))
    return "\n".join(parts)


def _fence(text: str) -> str:
    longest, run = 0, 0
    for ch in text:
        run = run + 1 if ch == "`" else 0
        longest = max(longest, run)
    return "`" * max(3, longest + 1)


def build_pack(root: Path, candidates: list, command: str, budget_tokens: int, max_file_bytes: int,
               repo: dict, notes: list) -> Pack:
    pack = Pack(command=command, budget_tokens=budget_tokens, repo=repo, notes=list(notes))
    remaining = int(budget_tokens * SAFETY) - estimate_tokens(command) - 200  # 200 = pack header
    per_file_cap = max(800, int(budget_tokens * MAX_FULL_SHARE))
    for cand in candidates:
        content = read_file(root, cand.path, max_file_bytes)
        if content.skipped:
            pack.not_included.append(f"{cand.path} ({content.skipped})")
            continue
        full_cost = estimate_tokens(content.text) + ITEM_OVERHEAD_TOKENS
        item = None
        if full_cost <= min(remaining, per_file_cap):
            item = PackItem(cand.path, "FULL", full_cost, cand.score, cand.reasons, content.language,
                            content.line_count, content.redactions, content.truncated, content.text)
        elif cand.ranges:
            window = _window_text(content, cand.ranges)
            window_cost = estimate_tokens(window) + ITEM_OVERHEAD_TOKENS
            if window_cost <= remaining:
                item = PackItem(cand.path, "WINDOW", window_cost, cand.score, cand.reasons, content.language,
                                content.line_count, content.redactions, content.truncated, window)
        if item is None and PATH_ONLY_TOKENS <= remaining:
            item = PackItem(cand.path, "PATH_ONLY", PATH_ONLY_TOKENS, cand.score, cand.reasons, content.language,
                            content.line_count)
        if item is None:
            pack.not_included.append(f"{cand.path} (budget exhausted)")
            continue
        remaining -= item.tokens
        pack.items.append(item)
    order = {"FULL": 0, "WINDOW": 1, "PATH_ONLY": 2}
    pack.items.sort(key=lambda i: (order[i.level], -i.score, i.path))
    pack.markdown = render_markdown(pack)
    return pack


def render_markdown(pack: Pack) -> str:
    s = pack.stats()
    approx = sum(i.tokens for i in pack.items)  # the markdown does not exist yet, so add up the items
    out = ["# Context pack", "", f"**Task:** {pack.command}", ""]
    if pack.repo.get("is_git_repo"):
        out.append(f"**Base commit:** `{pack.repo['base_commit']}` on `{pack.repo['branch']}`"
                   f" ({pack.repo['uncommitted_files']} uncommitted files)")
        out.append("")
    out.append(f"{s['fullFiles']} complete files, {s['windowedFiles']} excerpts, {s['pathOnlyFiles']} path-only "
               f"(~{approx} of {pack.budget_tokens} tokens).")
    out.append("")
    for item in pack.items:
        out.append(f"## {item.path}  `{item.level}`")
        out.append("*Why:* " + "; ".join(item.reasons[:4]) if item.reasons else "*Why:* related")
        if item.truncated:
            out.append("*(file was truncated at the size limit)*")
        if item.redactions:
            out.append(f"*({item.redactions} secret-looking values redacted)*")
        out.append("")
        if item.level != "PATH_ONLY":
            fence = _fence(item.text)
            out.extend([f"{fence}{item.language}", item.text.rstrip("\n"), fence, ""])
    if pack.not_included:
        out.append("## Not included")
        out.extend(f"- {line}" for line in pack.not_included[:30])
        out.append("")
    if pack.notes:
        out.append("## Notes")
        out.extend(f"- {n}" for n in pack.notes)
    return "\n".join(out).rstrip() + "\n"