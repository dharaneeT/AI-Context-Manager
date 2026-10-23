"""Turn raw build and test output into structured failures: kind, file, line, message, and a signature that stays
the same when only line numbers or absolute paths change (so the repair loop can tell "same error again")."""
from __future__ import annotations

import hashlib
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ENVIRONMENT = [
    (re.compile(r"is not recognized as an internal or external command|command not found|: not found$", re.M),
     "a required program is missing"),
    (re.compile(r"JAVA_HOME|No compiler is provided|release version \d+ not supported|invalid target release"),
     "the Java installation does not match the project"),
    (re.compile(r"Could not (?:resolve dependencies|transfer artifact)|UnknownHostException|Connection refused|ETIMEDOUT|ENOTFOUND|EAI_AGAIN"),
     "a dependency could not be downloaded (offline?)"),
    (re.compile(r"Cannot find module '(?![./])[^']+'|ENOENT: no such file or directory, open '.*node_modules"),
     "node_modules is missing or incomplete (run npm install in the real project)"),
    (re.compile(r"Address already in use|Port \d+ is already in use"), "a port is already in use"),
]
_DIGITS = re.compile(r"\d+|0x[0-9a-f]+", re.I)
_PATHS = re.compile(r"(?:[A-Za-z]:)?[\\/][\w .\\/-]+[\\/]")


def failure(kind: str, message: str, file: str | None = None, line: int | None = None, test: str | None = None,
            command: str = "") -> dict:
    return {"kind": kind, "file": file, "line": line, "test": test, "message": message.strip()[:500],
            "command": command, "signature": signature(kind, file, message, test)}


def signature(kind: str, file: str | None, message: str, test: str | None = None) -> str:
    """Same problem => same signature, even if the line number moved or the folder differs."""
    text = _PATHS.sub("", message.lower())
    text = _DIGITS.sub("#", text)
    text = re.sub(r"\s+", " ", text).strip()[:200]
    base = (file or "").replace("\\", "/").rsplit("/", 1)[-1]
    return hashlib.sha1(f"{kind}|{base}|{test or ''}|{text}".encode()).hexdigest()[:12]


def relativize(path: str, tree: Path) -> str:
    """Make a path from tool output relative to the workspace, with forward slashes."""
    p = path.strip().strip('"').replace("\\", "/")
    root = str(tree.resolve()).replace("\\", "/").rstrip("/") + "/"
    if p.lower().startswith(root.lower()):
        return p[len(root):]
    if not (tree / p.lstrip("/")).exists():  # an absolute path from another machine or a short temp path
        cuts = [i for i in (p.find(m) for m in ("/backend/", "/frontend/", "/src/")) if i > 0]
        if cuts:
            return p[min(cuts) + 1:]
    return p.lstrip("./") if p.startswith("./") else p


def classify_environment(output: str) -> str | None:
    for pattern, why in ENVIRONMENT:
        if pattern.search(output):
            return why
    return None


# ------------------------------------------------------------------------------------------------ Maven / Java
_MVN_ERROR = re.compile(r"^\[ERROR\]\s+(?P<file>.+?\.java):\[(?P<line>\d+),(?P<col>\d+)\]\s+(?P<msg>.+?)\s*$")
_MVN_DETAIL = re.compile(r"^(?:\[ERROR\])?\s{2,}(?P<key>symbol|location|required|found|reason):\s*(?P<val>.*?)\s*$")


def parse_maven_compile(output: str, tree: Path, command: str = "") -> list:
    out = []
    for raw in output.split("\n"):
        line = raw.rstrip("\r")
        m = _MVN_ERROR.match(line)
        if m:
            out.append(failure("compile", m["msg"], relativize(m["file"], tree), int(m["line"]), command=command))
            continue
        d = _MVN_DETAIL.match(line)
        if d and out and out[-1]["kind"] == "compile":
            out[-1]["message"] = (out[-1]["message"] + f" [{d['key']}: {d['val']}]")[:500]
            out[-1]["signature"] = signature("compile", out[-1]["file"], out[-1]["message"])
    seen, unique = set(), []
    for f in out:  # Maven prints each error twice (summary and detail)
        k = (f["file"], f["line"], f["message"])
        if k not in seen:
            seen.add(k)
            unique.append(f)
    return unique


def _find_source(tree: Path, simple_name: str) -> str | None:
    for hit in tree.rglob(simple_name + ".java"):
        if "target" not in hit.parts and "node_modules" not in hit.parts:
            return str(hit.relative_to(tree)).replace("\\", "/")
    return None


def parse_surefire(tree: Path, module_dir: str, since: float, command: str = "") -> tuple:
    """(failures, summary) from target/surefire-reports/TEST-*.xml written since `since`."""
    reports = []
    for sub in ("surefire-reports", "failsafe-reports"):
        reports += list((tree / module_dir / "target" / sub).glob("TEST-*.xml"))
    summary = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "suites": 0}
    fails = []
    for report in reports:
        if report.stat().st_mtime < since - 2:
            continue  # an old report from an earlier run
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError:
            continue
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for suite in suites:
            summary["suites"] += 1
            for key in ("tests", "failures", "errors", "skipped"):
                summary[key] += int(suite.attrib.get(key, 0) or 0)
            for case in suite.iter("testcase"):
                bad = next((c for c in case if c.tag in ("failure", "error")), None)
                if bad is None:
                    continue
                cls, name = case.attrib.get("classname", ""), case.attrib.get("name", "")
                stack = bad.text or ""
                frame = re.search(rf"\(({re.escape(cls.rsplit('.', 1)[-1])})\.java:(\d+)\)", stack)
                file = _find_source(tree, frame.group(1)) if frame else _find_source(tree, cls.rsplit(".", 1)[-1])
                message = bad.attrib.get("message") or stack.strip().split("\n")[0] or bad.attrib.get("type", "test failed")
                fails.append(failure("test", message, file, int(frame.group(2)) if frame else None,
                                     f"{cls.rsplit('.', 1)[-1]}.{name}", command))
    return fails, summary


_MVN_TEST_LINE = re.compile(r"^\[ERROR\]\s+(?P<test>\w+\.\w+):(?P<line>\d+)\s+(?P<msg>.+)$")


def parse_maven_test_text(output: str, command: str = "") -> list:
    """Fallback when no XML report exists: the summary lines Maven prints."""
    return [failure("test", m["msg"], None, int(m["line"]), m["test"], command)
            for l in output.split("\n") if (m := _MVN_TEST_LINE.match(l.rstrip("\r")))]


# ------------------------------------------------------------------------------------------------ Node
_ESBUILD = re.compile(r"^(?P<file>.+?):(?P<line>\d+):(?P<col>\d+):\s*ERROR:\s*(?P<msg>.+?)\s*$")
_ROLLUP = re.compile(r"^(?P<file>\S+?\.\w+)\s+\((?P<line>\d+):(?P<col>\d+)\):\s*(?P<msg>.+?)\s*$")
_RESOLVE = re.compile(r'Failed to resolve import "(?P<imp>[^"]+)" from "(?P<file>[^"]+)"')
_ESLINT_FILE = re.compile(r"^(?P<file>(?:[A-Za-z]:)?[^\s].*\.(?:jsx?|tsx?|mjs|cjs))\s*$")
_ESLINT_ROW = re.compile(r"^\s+(?P<line>\d+):(?P<col>\d+)\s+error\s+(?P<msg>.+?)(?:\s{2,}(?P<rule>[\w@/-]+))?\s*$")
_JEST_FAIL = re.compile(r"^\s*(?:FAIL|❯|×|✗)\s+(?P<file>\S+\.(?:jsx?|tsx?))(?:\s*>\s*(?P<name>.+))?")


def parse_node_build(output: str, tree: Path, module_dir: str, command: str = "") -> list:
    out = []
    for raw in output.split("\n"):
        line = raw.rstrip("\r")
        m = _ESBUILD.match(line.strip())
        if m:
            out.append(failure("build", m["msg"], relativize(m["file"], tree), int(m["line"]), command=command))
            continue
        m = _ROLLUP.match(line.strip())
        if m:
            out.append(failure("build", m["msg"], relativize(m["file"], tree), int(m["line"]), command=command))
            continue
        m = _RESOLVE.search(line)
        if m:
            out.append(failure("build", f'Failed to resolve import "{m["imp"]}"', relativize(m["file"], tree),
                               command=command))
    return out


def parse_eslint(output: str, tree: Path, command: str = "") -> list:
    out, current = [], None
    for raw in output.split("\n"):
        line = raw.rstrip("\r")
        f = _ESLINT_FILE.match(line)
        if f:
            current = relativize(f["file"], tree)
            continue
        row = _ESLINT_ROW.match(line)
        if row and current:
            msg = row["msg"] + (f" ({row['rule']})" if row["rule"] else "")
            out.append(failure("lint", msg, current, int(row["line"]), command=command))
    return out


def parse_js_tests(output: str, tree: Path, command: str = "") -> list:
    out, lines = [], output.split("\n")
    for i, raw in enumerate(lines):
        m = _JEST_FAIL.match(raw)
        if not m:
            continue
        msg = next((l.strip() for l in lines[i + 1:i + 16] if re.search(r"(Assertion|Type|Reference)?Error:|expected", l)),
                   "test failed")
        out.append(failure("test", msg, relativize(m["file"], tree), None, m["name"], command))
    return out


# ------------------------------------------------------------------------------------------------ the dispatcher
def last_lines(output: str, n: int = 6) -> str:
    useful = [l.strip() for l in output.split("\n") if l.strip() and not l.strip().startswith(("at ", "[INFO]"))]
    return "\n".join(useful[-n:])[:500]


def parse_result(result: dict, profile: dict, tree: Path, started: float) -> tuple:
    """(failures, summary or None, warnings) for one finished command."""
    key = result["command"]
    cmd = (profile.get("commands") or {}).get(key, {})
    label, _, kind = key.partition(":")
    module = next((m for m in profile.get("modules", []) if m["label"] == label), {})
    output = result["output"]
    fails, summary, warnings = [], None, []
    if result["timed_out"]:
        return [failure("timeout", f"{key} did not finish in time and was stopped", command=key)], None, warnings
    why = result.get("environment_problem") and "a required program is missing" or (
        classify_environment(output) if result["exit_code"] != 0 else None)
    if why:
        return [failure("environment", f"{why}: {last_lines(output, 2)}", command=key)], None, warnings
    if module.get("kind") == "maven":
        if kind in ("compile", "build") or "COMPILATION ERROR" in output:
            fails = parse_maven_compile(output, tree, key)
        if kind == "test" and not fails:
            fails, summary = parse_surefire(tree, cmd.get("cwd", module.get("dir", "")), started, key)
            if not fails and result["exit_code"] != 0:
                fails = parse_maven_text_or_none(output, key)
            if result["exit_code"] == 0 and summary and summary["tests"] == 0:
                warnings.append("no tests were executed, so a pass here proves little")
    elif module.get("kind") == "node":
        if kind == "lint":
            fails = parse_eslint(output, tree, key)
        elif kind == "test":
            fails = parse_js_tests(output, tree, key)
        else:
            fails = parse_node_build(output, tree, cmd.get("cwd", ""), key)
    if result["exit_code"] != 0 and not fails:
        label_kind = {"compile": "compile", "build": "build", "test": "test", "lint": "lint"}.get(kind, "unknown")
        fails = [failure(label_kind, f"{key} failed (exit code {result['exit_code']}): {last_lines(output)}", command=key)]
    return fails, summary, warnings


def parse_maven_text_or_none(output: str, key: str) -> list:
    return parse_maven_test_text(output, key)
