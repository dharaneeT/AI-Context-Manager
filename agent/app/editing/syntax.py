"""Quick syntax checks right after every edit, so a broken file is caught in milliseconds instead of after a
two-minute Maven build. They catch typical model mistakes (unbalanced braces, bad JSON/XML/Python). The real
verdict is always the compiler on Day 14."""
from __future__ import annotations

import ast
import json
import re
import xml.etree.ElementTree as ET

PAIRS = {")": "(", "]": "[", "}": "{"}
PUBLIC_TYPE = re.compile(r"^\s*public\s+(?:(?:final|abstract|sealed|static)\s+)*(?:class|interface|enum|record|@interface)\s+(\w+)", re.M)


def _balance(text: str, language: str) -> list:
    """Bracket balance that understands strings, chars, text blocks and comments of Java and JavaScript."""
    stack, i, n, line = [], 0, len(text), 1
    while i < n:
        c, nxt = text[i], text[i + 1] if i + 1 < n else ""
        if c == "\n":
            line += 1
        if c == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if c == "/" and nxt == "*":
            end = text.find("*/", i + 2)
            end = n if end == -1 else end + 2
            line += text.count("\n", i, end)
            i = end
            continue
        if language == "java" and text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end == -1 else end + 3
            line += text.count("\n", i, end)
            i = end
            continue
        if c in "\"'" or (language == "js" and c == "`"):
            quote, j = c, i + 1
            while j < n and text[j] != quote:
                if text[j] == "\\":
                    j += 1
                elif text[j] == "\n" and quote != "`":
                    break
                j += 1
            line += text.count("\n", i, j)
            i = j + 1
            continue
        if c in "([{":
            stack.append((c, line))
        elif c in ")]}":
            if not stack:
                return [f"line {line}: unexpected '{c}'"]
            opener, opened = stack.pop()
            if opener != PAIRS[c]:
                return [f"line {line}: '{c}' does not match '{opener}' opened on line {opened}"]
        i += 1
    if stack:
        opener, opened = stack[-1]
        return [f"'{opener}' opened on line {opened} is never closed"]
    return []


def check_syntax(path: str, text: str) -> dict:
    """{'problems': [...], 'hard': bool}. A hard problem means the edit must be rolled back."""
    name = path.rsplit("/", 1)[-1]
    ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    problems, hard = [], True
    try:
        if ext == "java":
            problems = _balance(text, "java")
            stem = name[:-5]
            declared = PUBLIC_TYPE.findall(text)
            if not problems and declared and stem not in declared:
                problems.append(f"the public type is '{declared[0]}' but the file is named {name}")
        elif ext == "py":
            ast.parse(text)
        elif ext == "json":
            json.loads(text)
        elif ext in ("xml",) or name == "pom.xml":
            ET.fromstring(text.encode("utf-8"))
        elif ext in ("js", "ts", "mjs"):
            problems = _balance(text, "js")
        elif ext in ("jsx", "tsx"):
            problems, hard = _balance(text, "js"), False  # JSX text can contain apostrophes: warn, do not roll back
    except (SyntaxError, ValueError, ET.ParseError) as e:
        problems = [f"{type(e).__name__}: {e}"]
    return {"problems": problems, "hard": hard and bool(problems)}