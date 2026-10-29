"""What the model sees when repairing: the error, the code around it, and (for 'cannot find symbol') the real
declaration of the thing that is missing, so it fixes the call instead of guessing."""
from __future__ import annotations

import re
from pathlib import Path

from app.context.files import IGNORED_DIRS
from app.implement.context import outline
from app.llm.base import estimate_tokens
from app.llm.prompts import render

SYMBOL = [re.compile(r"symbol:\s*(?:method|class|variable|constructor)\s+(?P<n>\w+)"),
          re.compile(r"cannot find symbol[^\n]*?\b(?P<n>[A-Za-z_]\w+)\b\s*$"),
          re.compile(r"[\"']?(?P<n>[A-Za-z_]\w+)[\"']? is not defined"),
          re.compile(r"[\"'](?P<n>[A-Za-z_]\w+)[\"'] is not exported"),
          re.compile(r"Failed to resolve import [\"'](?P<n>[^\"']+)[\"']")]
RANK = {"compile": 0, "build": 1, "test": 2, "lint": 3, "unknown": 4}
ASSERTION = re.compile(r"\b(assert\w*|expect|verify|should|fail)\b", re.I)


def symbol_from(message: str) -> str | None:
    for pattern in SYMBOL:
        m = pattern.search(message)
        if m:
            return m["n"].rsplit("/", 1)[-1].lstrip("./")
    return None


def window(tree: Path, rel: str, line: int | None, margin: int = 25) -> str:
    path = tree / rel
    if not path.is_file():
        return f"(file {rel} not found)"
    lines = path.read_text(encoding="utf-8", errors="replace").replace("\r\n", "\n").split("\n")
    if line is None:
        a, b = 1, min(len(lines), 2 * margin)
    else:
        a, b = max(1, line - margin), min(len(lines), line + margin)
    body = "\n".join(f"{i}: {lines[i - 1]}" for i in range(a, b + 1))
    return f"=== {rel} (lines {a}-{b} of {len(lines)})\n{body}"


def find_declarations(tree: Path, name: str, limit: int = 3) -> list:
    """Where is `name` declared? Looks at Java and JavaScript sources in the workspace (never build output)."""
    java = re.compile(rf"\b(?:class|interface|enum|record)\s+{re.escape(name)}\b|"
                      rf"^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|protected|private|static|final|default|abstract|synchronized)\s+)*"
                      rf"[\w<>\[\],.? ]+\s+{re.escape(name)}\s*\([^;{{]*\)?\s*(?:throws [\w, .]+)?\s*[{{;]?\s*$")
    js = re.compile(rf"\b(?:function|const|let|var|class)\s+{re.escape(name)}\b|export\s+default\s+function\s+{re.escape(name)}\b")
    hits = []
    for path in tree.rglob("*"):
        if len(hits) >= limit:
            break
        if not path.is_file() or path.suffix not in (".java", ".js", ".jsx", ".ts", ".tsx") or \
                any(part in IGNORED_DIRS for part in path.relative_to(tree).parts):
            continue
        pattern = java if path.suffix == ".java" else js
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").split("\n")
        except OSError:
            continue
        for i, l in enumerate(lines):
            if pattern.search(l) and not l.strip().startswith(("return ", "new ", "throw ", "//", "*")):
                rel = str(path.relative_to(tree)).replace("\\", "/")
                hits.append(f"=== {rel}:{i + 1}\n" + "\n".join(lines[max(0, i - 1):i + 4]))
                break
    return hits


def weakens_test(path: str, search: str, replace: str) -> bool:
    """A repair must not 'fix' a failing test by deleting or loosening its assertions."""
    low = path.lower()
    if "/test/" not in low and "/tests/" not in low and "__tests__" not in low and not re.search(r"(test|tests|spec)\.\w+$", low) \
            and not path.rsplit("/", 1)[-1].lower().startswith("test_"):
        return False
    return bool(search.strip()) and len(ASSERTION.findall(replace)) < len(ASSERTION.findall(search))


def pick_targets(failures: list, limit: int = 3) -> list:
    seen, out = set(), []
    for f in sorted(failures, key=lambda f: RANK.get(f["kind"], 9)):
        if f["signature"] not in seen:
            seen.add(f["signature"])
            out.append(f)
        if len(out) >= limit:
            break
    return out


def build_repair_prompt(settings, run: dict, tree: Path, targets: list, allowed: set, attempt: int, previous: list):
    budget = settings.prompt_budget_tokens
    per = max(300, int(budget * 0.40) // max(1, len(targets)))
    failures, windows, decls, shown = [], [], [], set()
    for f in targets:
        where = f"{f['file']}:{f['line']}" if f.get("file") and f.get("line") else (f.get("file") or f["command"])
        failures.append(f"- [{f['kind']}] {where}: {f['message']}" + (f" (test {f['test']})" if f.get("test") else ""))
        if f.get("file") and (f["file"], f.get("line")) not in shown:
            shown.add((f["file"], f.get("line")))
            w = window(tree, f["file"], f.get("line"), 22)
            windows.append(w[: per * 4])
        name = symbol_from(f["message"])
        if name:
            decls += find_declarations(tree, name, 2) or [f"(no declaration of '{name}' was found in the project)"]
    dec_text = "\n\n".join(dict.fromkeys(decls))[: int(budget * 0.2) * 4]
    prev = "\n".join(f"- attempt {p['n']}: changed {', '.join(p['edits']) or 'nothing'}; then: {p['result']}"
                     for p in previous) or "(none)"
    understanding = run.get("understanding") or {}
    prompt = render("repair_failure", summary=understanding.get("summary") or run["command"],
                    command=targets[0]["command"], attempt=attempt, max=settings.max_repair_attempts,
                    failures="\n".join(failures), windows="\n\n".join(windows) or "(none)",
                    declarations=dec_text or "(none)", previous=prev, allowed=", ".join(sorted(allowed)) or "(none)")
    return prompt