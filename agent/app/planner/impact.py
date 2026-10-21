"""Impact analysis: which layers and files a task touches, whether to extend or create, and what is risky.

Deliberately deterministic (no model): it is built from the search, graph, symbol and profile evidence the agent
already has, so it is fast, repeatable and testable. The model only sees its compact result on Day 9."""
from __future__ import annotations

import re

from app.context.testmap import CONFIG_TASK_WORDS, is_test_path

LAYER_ORDER = ["config", "migration", "entity", "repository", "service", "dto", "controller", "java-other",
               "api-client", "ui-component", "frontend-other", "style", "other", "test"]
MODIFY_SCORE = 0.45
MAX_MODIFY = 8
CROSS_CUTTING = {"pagination", "sorting", "filtering", "filter", "search", "caching", "cache", "logging", "validation",
                 "performance", "timeout", "error", "errors", "handling", "refactor", "rename", "bug", "fix",
                 "layout", "styling", "style", "theme", "button", "label", "message", "text", "docs", "documentation"}
GENERIC = {"feature", "function", "functionality", "option", "support", "ability", "system", "application", "app",
           "user", "users", "page", "screen", "list", "data", "thing", "completed", "existing", "stars", "star",
           "should", "must", "project", "task", "tasks"}
INTENT_ADD = re.compile(r"\b(add|create|introduce|implement|support|new|build)\b", re.I)
INTENT_NOT_ADD = re.compile(r"\b(fix|bug|refactor|rename|improve|optimi[sz]e|speed up)\b", re.I)


def classify(path: str, annotations: str = "") -> str:
    name = path.rsplit("/", 1)[-1]
    stem = name.rsplit(".", 1)[0]
    ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    low = path.lower()
    if is_test_path(path):
        return "test"
    if name in ("pom.xml", "package.json", "docker-compose.yml", "requirements.txt", "pyproject.toml") \
            or ext in ("properties", "yml", "yaml"):
        return "config"
    if ext == "java":
        if "@Entity" in annotations or "@Table" in annotations:
            return "entity"
        if "@RestController" in annotations or "@Controller" in annotations or stem.endswith("Controller"):
            return "controller"
        if "@Repository" in annotations or stem.endswith("Repository"):
            return "repository"
        if stem.endswith(("Properties", "Config", "Configuration")) or "@ConfigurationProperties" in annotations:
            return "config"
        if "@Service" in annotations or stem.endswith("Service"):
            return "service"
        if stem.endswith(("Dto", "Request", "Response")) or "/dto/" in low:
            return "dto"
        return "java-other"
    if ext in ("jsx", "tsx"):
        return "ui-component"
    if ext in ("js", "ts"):
        return "api-client" if stem.lower() in ("api", "client") or "/api/" in low else "frontend-other"
    if ext in ("css", "scss"):
        return "style"
    if ext == "sql":
        return "migration"
    return "other"


def _singular(word: str) -> str:
    return word[:-1] if word.endswith("s") and not word.endswith("ss") and len(word) > 4 else word


def concepts_of(command: str, keywords: list) -> list:
    """Candidate new-feature nouns: words that follow add/create/new, else the first uncommon keywords."""
    after = re.findall(r"\b(?:add|create|introduce|implement|new)\s+(?:an?\s+|the\s+)?([a-z][a-z0-9]+)", command.lower())
    pool = after + [k for k in keywords if k not in after]
    out = []
    for w in pool:
        w = _singular(w)
        if len(w) > 3 and w not in GENERIC and w not in CROSS_CUTTING and w not in out:
            out.append(w)
    return out[:2]


def suggest_files(concept: str, profile: dict, flags: dict, all_files: list) -> list:
    """Where a new feature's files would go, following the project's own layout and naming."""
    j = profile.get("java") or {}
    out = []
    exists = set(all_files)
    pascal = concept[:1].upper() + concept[1:]
    if j and flags.get("backend", True):
        module = f"{j['module_dir']}/" if j.get("module_dir") else ""
        main = f"{module}src/main/java/{j['base_dir']}"
        test = f"{module}src/test/java/{j['base_dir']}"
        if j.get("layout") == "by-layer":
            dirs = {k: f"{main}/{k}" for k in ("entity", "repository", "service", "controller", "dto")}
            test_dir = f"{test}/service"
        else:
            dirs = {k: f"{main}/{concept}" for k in ("entity", "repository", "service", "controller", "dto")}
            test_dir = f"{test}/{concept}"
        names = {"entity": f"{pascal}.java", "repository": f"{pascal}Repository.java",
                 "service": f"{pascal}Service.java", "controller": f"{pascal}Controller.java",
                 "dto": f"{pascal}Dto.java"}
        for layer in ("entity", "repository", "service", "controller", "dto"):
            if layer == "entity" and not flags.get("database"):
                continue
            if layer == "repository" and not flags.get("database"):
                continue
            out.append({"path": f"{dirs[layer]}/{names[layer]}", "layer": layer,
                        "why": f"new {layer} for '{concept}', following this project's {j.get('layout')} layout"})
        out.append({"path": f"{test_dir}/{pascal}Service{j.get('test_suffix', 'Test')}.java", "layer": "test",
                    "why": f"tests for the new {pascal}Service"})
    fe = profile.get("frontend") or {}
    if fe and flags.get("frontend"):
        out.append({"path": f"{fe['component_dir']}/{pascal}Panel.{fe['component_ext']}", "layer": "ui-component",
                    "why": f"new UI component for '{concept}'"})
    return [f for f in out if f["path"] not in exists]


def compute_impact(command: str, understanding: dict, profile: dict, candidates: list, annotations: dict,
                   dependents: dict, all_files: list) -> dict:
    criteria = " ".join((understanding.get("acceptance_criteria") or []) + (understanding.get("constraints") or []))
    text = f"{command} {criteria}"
    areas = set(understanding.get("areas") or [])
    config_needed = bool(CONFIG_TASK_WORDS.search(command)) or "config" in areas

    components, modify_count = [], 0
    for c in sorted(candidates, key=lambda c: -c["score"]):
        layer = classify(c["path"], annotations.get(c["path"], ""))
        if layer == "test":
            role = "test"
        elif layer in ("config", "migration"):
            role = "modify" if config_needed and c["score"] >= 0.3 else "reference"
        elif c["score"] >= MODIFY_SCORE and modify_count < MAX_MODIFY:
            role, modify_count = "modify", modify_count + 1
        else:
            role = "reference"
        components.append({"path": c["path"], "layer": layer, "role": role, "score": c["score"],
                           "why": "; ".join(c["reasons"][:2])})

    touched = sorted({x["layer"] for x in components if x["role"] == "modify"}, key=LAYER_ORDER.index)
    flags = {
        "database": bool(set(touched) & {"entity", "repository", "migration"}) or "database" in areas
                    or bool(re.search(r"\b(column|table|persist|stored?|save[sd]?|database|migration|schema)\b", text, re.I)),
        "authorization": bool(re.search(r"\b(auth\w*|permission|roles?|owner|admin|private|participants?|log ?in|access)\b", text, re.I)),
        "validation": bool(re.search(r"\b(valid\w*|range|between|required|limit|min(imum)?|max(imum)?|at least|at most|\d+ ?(to|-) ?\d+|unique|only once)\b", text, re.I)),
        "frontend": bool(set(touched) & {"ui-component", "api-client", "style"}) or "frontend" in areas,
        "backend": bool(set(touched) & {"entity", "repository", "service", "dto", "controller", "java-other"}) or "backend" in areas,
        "config": "config" in touched,
        "spring_security_present": any("Spring Security" in m.get("frameworks", []) for m in profile.get("modules", [])),
    }

    entities = {c["path"].rsplit("/", 1)[-1][:-5].lower(): c["path"] for c in components if c["layer"] == "entity"}
    decisions, new_files = [], []
    wants_new = bool(INTENT_ADD.search(command)) and not INTENT_NOT_ADD.search(command)
    for concept in concepts_of(command, understanding.get("keywords") or []):
        match = next((p for name, p in entities.items() if name in concept or concept in name), None)
        existing_named = next((c["path"] for c in components if concept in c["path"].lower()), None)
        if match:
            decisions.append({"concept": concept, "decision": "extend", "target": match,
                              "why": f"an existing entity already models '{concept}'"})
        elif existing_named and not wants_new:
            decisions.append({"concept": concept, "decision": "extend", "target": existing_named,
                              "why": f"existing code already mentions '{concept}'"})
        elif wants_new:
            decisions.append({"concept": concept, "decision": "create", "target": None,
                              "why": f"nothing in the project models '{concept}' yet (heuristic: review this)"})
            new_files += suggest_files(concept, profile, flags, all_files)

    risks = []
    if len(touched) >= 4:
        risks.append(f"The change spans {len(touched)} layers ({', '.join(touched)}): do it in small, testable steps.")
    for path, count in dependents.items():
        if count >= 8:
            risks.append(f"{path} is used by {count} files: changes there can break many callers.")
    tests_for = {r for c in components if c["role"] == "test" for r in c["why"].split("; ") if r.startswith("test for ")}
    for x in components:
        if x["role"] == "modify" and x["layer"] not in ("config", "test") and f"test for {x['path']}" not in tests_for:
            risks.append(f"No existing test found for {x['path']}: the plan should add one.")
    if flags["database"]:
        risks.append("Data model change: check how this project migrates its schema before running it on real data.")
    if flags["authorization"] and not flags["spring_security_present"]:
        risks.append("The task involves who may do something, but no security framework was detected: "
                     "authorization must be implemented by hand.")
    return {"components": components, "layers_touched": touched, "flags": flags, "decisions": decisions,
            "suggested_new_files": new_files, "risks": risks[:8]}