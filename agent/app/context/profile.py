"""Project profile: how this project is built, tested and written, detected from its own files.

The profile answers three questions for every later stage: WHICH COMMANDS can verify a change, WHERE do new files
go, and HOW is code written here (naming, indentation, layout). Cached per project until a build file or the git
commit changes."""
from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

from app.context.files import map_root, list_project_files
from app.context.git import repo_state

BUILD_FILES = {"pom.xml": "maven", "build.gradle": "gradle", "build.gradle.kts": "gradle",
               "package.json": "node", "pyproject.toml": "python", "requirements.txt": "python"}
LAYER_DIRS = {"controller", "service", "repository", "entity", "model", "dto"}
SUFFIXES = ["Controller", "Service", "Repository", "Dto", "Properties", "Exception", "Config", "Client", "Mapper",
            "Entity", "Request", "Response"]
STYLE_FILES = {".editorconfig", "eslint.config.js", ".eslintrc", ".eslintrc.json", ".prettierrc", ".prettierrc.json",
               "checkstyle.xml"}
WINDOWS = sys.platform == "win32"


def _cmd(cwd: str, argv: list, available: bool = True, reason: str = "") -> dict:
    return {"cwd": cwd, "argv": argv, "available": available, "reason": reason}


def _label(directory: str, kind: str) -> str:
    return directory.rsplit("/", 1)[-1] if directory else kind


def _strip_ns(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse_pom(path: Path) -> dict:
    """artifactId list, parent, java version, from a pom.xml (namespace-safe, no network)."""
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        return {"dependencies": [], "parent": None, "java": None}
    deps, parent, java = [], None, None
    for child in root:
        name = _strip_ns(child.tag)
        if name == "parent":
            parent = next((c.text for c in child if _strip_ns(c.tag) == "artifactId"), None)
        elif name == "properties":
            java = next((c.text for c in child if _strip_ns(c.tag) in ("java.version", "maven.compiler.release")), None)
        elif name == "dependencies":
            for dep in child:
                aid = next((c.text for c in dep if _strip_ns(c.tag) == "artifactId"), None)
                if aid:
                    deps.append(aid.strip())
    return {"dependencies": deps, "parent": parent, "java": java.strip() if java else None}


def _maven_module(root: Path, directory: str, label: str) -> tuple:
    info = parse_pom(root / directory / "pom.xml")
    deps = info["dependencies"]
    wrapper = (root / directory / "mvnw").exists() or (root / directory / "mvnw.cmd").exists()
    exe = ("mvnw.cmd" if WINDOWS else "./mvnw") if wrapper else "mvn"
    spring = info["parent"] == "spring-boot-starter-parent" or any(d.startswith("spring-boot-starter") for d in deps)
    test_deps = [d for d in deps if "junit" in d or "mockito" in d or d.endswith("-test") or "testcontainers" in d]
    frameworks = (["Spring Boot"] if spring else []) + (["Lombok"] if "lombok" in deps else []) \
        + (["Spring Security"] if any("security" in d for d in deps) else [])
    cmds = {
        f"{label}:compile": _cmd(directory, [exe, "-q", "-DskipTests", "compile"]),
        f"{label}:test": _cmd(directory, [exe, "-q", "test"], bool(test_deps),
                              "" if test_deps else "no test dependency in pom.xml"),
    }
    module = {"kind": "maven", "dir": directory, "label": label, "language": "java", "java": info["java"],
              "frameworks": frameworks, "test_frameworks": sorted(set(test_deps))[:6], "wrapper": wrapper,
              "dependencies": deps[:40]}
    return module, cmds


def _node_module(root: Path, directory: str, label: str) -> tuple:
    try:
        data = json.loads((root / directory / "package.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        data = {}
    scripts = data.get("scripts", {}) or {}
    deps = {**(data.get("dependencies") or {}), **(data.get("devDependencies") or {})}
    npm = "npm.cmd" if WINDOWS else "npm"
    frameworks = [n for key, n in (("react", "React"), ("vue", "Vue"), ("vite", "Vite"), ("next", "Next.js"),
                                   ("typescript", "TypeScript")) if key in deps]
    tf = [k for k in deps if k in ("vitest", "jest", "mocha", "cypress", "playwright") or k.startswith("@testing-library")]
    cmds = {}
    for script in ("build", "test", "lint"):
        present = script in scripts
        cmds[f"{label}:{script}"] = _cmd(directory, [npm, "run", script] if script != "test" else [npm, "test"],
                                         present, "" if present else f"no '{script}' script in package.json")
    module = {"kind": "node", "dir": directory, "label": label, "language": "javascript", "frameworks": frameworks,
              "test_frameworks": tf, "scripts": sorted(scripts)}
    return module, cmds


def _python_module(root: Path, directory: str, label: str, files: list) -> tuple:
    text = ""
    for name in ("requirements.txt", "pyproject.toml"):
        p = root / directory / name
        if p.is_file():
            text += p.read_text(encoding="utf-8", errors="replace").lower()
    pytest = "pytest" in text
    has_tests = any(f.startswith(f"{directory}/tests/".lstrip("/")) or f.startswith(f"{directory}/test_".lstrip("/"))
                    for f in files)
    argv = ["python", "-m", "pytest", "-q"] if pytest else ["python", "-m", "unittest", "discover"]
    cmds = {f"{label}:test": _cmd(directory, argv, has_tests or pytest, "" if has_tests or pytest else "no tests found")}
    return {"kind": "python", "dir": directory, "label": label, "language": "python", "frameworks": [],
            "test_frameworks": ["pytest"] if pytest else ["unittest"]}, cmds


def _indent_style(root: Path, paths: list) -> str:
    tabs = spaces2 = spaces4 = 0
    for rel in paths[:40]:
        try:
            with open(root / rel, "r", encoding="utf-8", errors="replace") as handle:
                head = handle.read(4096)
        except OSError:
            continue
        for line in head.splitlines():
            if line.startswith("\t"):
                tabs += 1
            elif re.match(r"^ {4}\S", line) or re.match(r"^ {8}\S", line):
                spaces4 += 1
            elif re.match(r"^ {2}\S", line) or re.match(r"^ {6}\S", line):
                spaces2 += 1
    best = max((tabs, "tabs"), (spaces4, "4 spaces"), (spaces2, "2 spaces"))
    return best[1] if best[0] else "unknown"


def _java_layout(files: list) -> dict:
    mains = [f for f in files if "/src/main/java/" in "/" + f and f.endswith(".java")]
    if not mains:
        return {}
    dirs = []
    for f in mains:
        after = ("/" + f).split("/src/main/java/", 1)[1]
        dirs.append(after.rsplit("/", 1)[0].split("/") if "/" in after else [])
    prefix = os.path.commonprefix(dirs)
    base = ".".join(prefix)
    module_dir = ("/" + mains[0]).split("/src/main/java/", 1)[0].lstrip("/")
    firsts = Counter(d[len(prefix)] for d in dirs if len(d) > len(prefix))
    layer_hits = {name for name in firsts if name.lower() in LAYER_DIRS}
    stems = [f.rsplit("/", 1)[-1][:-5] for f in mains]
    suffix_counts = {s: sum(1 for st in stems if st.endswith(s) and st != s) for s in SUFFIXES}
    tests = [f.rsplit("/", 1)[-1][:-5] for f in files if "/src/test/java/" in "/" + f and f.endswith(".java")]
    test_suffix = Counter("Tests" if t.endswith("Tests") else "IT" if t.endswith("IT") else "Test" for t in tests
                          if t.endswith(("Test", "Tests", "IT")))
    return {"module_dir": module_dir, "base_package": base, "base_dir": "/".join(prefix),
            "layout": "by-layer" if len(layer_hits) >= 2 else "by-feature",
            "feature_packages": [f"{n} ({c})" for n, c in firsts.most_common(14)],
            "class_suffixes": {k: v for k, v in suffix_counts.items() if v},
            "test_suffix": test_suffix.most_common(1)[0][0] if test_suffix else "Test"}


def _frontend_layout(files: list) -> dict:
    ui = [f for f in files if f.endswith((".jsx", ".tsx"))]
    if not ui:
        return {}
    dirs = Counter(f.rsplit("/", 1)[0] for f in ui)
    return {"component_dir": dirs.most_common(1)[0][0], "component_ext": Counter(f.rsplit(".", 1)[-1] for f in ui).most_common(1)[0][0],
            "component_count": len(ui)}


def detect_profile(root: Path, files: list) -> dict:
    modules, commands = [], {}
    seen = set()
    for f in files:
        name = f.rsplit("/", 1)[-1]
        directory = f.rsplit("/", 1)[0] if "/" in f else ""
        if name in BUILD_FILES and directory.count("/") <= 1 and (directory, BUILD_FILES[name]) not in seen:
            kind = BUILD_FILES[name]
            seen.add((directory, kind))
            label = _label(directory, kind)
            if kind == "maven":
                module, cmds = _maven_module(root, directory, label)
            elif kind == "node":
                module, cmds = _node_module(root, directory, label)
            elif kind == "python":
                module, cmds = _python_module(root, directory, label, files)
            else:  # gradle: recognised, but no commands yet
                module, cmds = {"kind": "gradle", "dir": directory, "label": label, "language": "java",
                                "frameworks": [], "test_frameworks": []}, {}
            modules.append(module)
            commands.update(cmds)
    ext = Counter(f.rsplit(".", 1)[-1].lower() for f in files if "." in f.rsplit("/", 1)[-1])
    java_files = [f for f in files if f.endswith(".java")]
    js_files = [f for f in files if f.endswith((".js", ".jsx", ".ts", ".tsx"))]
    style = sorted({f for f in files if f.rsplit("/", 1)[-1] in STYLE_FILES})
    return {
        "modules": modules,
        "commands": commands,
        "java": _java_layout(files),
        "frontend": _frontend_layout(files),
        "indent": {k: v for k, v in (("java", _indent_style(root, java_files)),
                                     ("javascript", _indent_style(root, js_files))) if files and v != "unknown"},
        "style_files": style,
        "languages": dict(ext.most_common(8)),
        "file_count": len(files),
    }


def fingerprint(root: Path, files: list, head: str | None) -> str:
    h = hashlib.sha1((head or "no-git").encode())
    h.update(str(len(files)).encode())
    for f in files:
        if f.rsplit("/", 1)[-1] in BUILD_FILES:
            try:
                st = (root / f).stat()
                h.update(f"{f}:{st.st_size}:{st.st_mtime_ns}".encode())
            except OSError:
                pass
    return h.hexdigest()


def get_profile(services, project_id: int, force: bool = False) -> dict:
    """The cached profile if nothing relevant changed, otherwise a fresh one (and the cache is updated)."""
    root = map_root(services.backend.project(project_id)["rootPath"], services.settings.path_map)
    files = list_project_files(root)
    state = repo_state(root)
    fp = fingerprint(root, files, state.get("base_commit"))
    cache_dir = Path(services.settings.data_dir) / "profiles"
    cache_dir.mkdir(parents=True, exist_ok=True)
    cache = cache_dir / f"{project_id}.json"
    if not force and cache.is_file():
        try:
            saved = json.loads(cache.read_text(encoding="utf-8"))
            if saved.get("fingerprint") == fp:
                return {**saved["profile"], "fingerprint": fp, "cached": True}
        except (OSError, json.JSONDecodeError, KeyError):
            pass
    profile = detect_profile(root, files)
    cache.write_text(json.dumps({"fingerprint": fp, "profile": profile}, indent=2), encoding="utf-8")
    return {**profile, "fingerprint": fp, "cached": False}


def available_commands(profile: dict) -> list:
    return sorted(k for k, v in (profile.get("commands") or {}).items() if v.get("available"))


def conventions_text(profile: dict, max_chars: int = 900) -> str:
    """A compact summary a small model can read (a few hundred tokens)."""
    if not profile:
        return ""
    lines = []
    for m in profile.get("modules", []):
        bits = [m["kind"]] + ([f"Java {m['java']}"] if m.get("java") else []) + m.get("frameworks", [])
        lines.append(f"Module '{m['label']}' ({m['dir'] or '.'}): " + ", ".join(bits))
    j = profile.get("java") or {}
    if j:
        lines.append(f"Java base package {j['base_package']}, layout {j['layout']}; "
                     f"packages: {', '.join(j.get('feature_packages', [])[:8])}.")
        if j.get("class_suffixes"):
            lines.append("Class suffixes: " + ", ".join(f"{k} x{v}" for k, v in j["class_suffixes"].items()) +
                         f"; tests end with {j.get('test_suffix', 'Test')}.")
    fe = profile.get("frontend") or {}
    if fe:
        lines.append(f"Frontend components live in {fe['component_dir']} (.{fe['component_ext']}).")
    if profile.get("indent"):
        lines.append("Indentation: " + ", ".join(f"{k} {v}" for k, v in profile["indent"].items()) + ".")
    cmds = available_commands(profile)
    if cmds:
        lines.append("Verification commands: " + ", ".join(cmds) + ".")
    return "\n".join(lines)[:max_chars]