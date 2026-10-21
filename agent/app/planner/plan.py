"""The implementation plan: its shape, the rules every plan must satisfy, and a deterministic template plan.

The same `validate_plan` guards a plan written by the model, a template plan, and (Day 10) a plan edited by a person."""
from __future__ import annotations

import re
from datetime import datetime, timezone

from app.context.files import is_ignored
from app.planner.impact import LAYER_ORDER, classify

ACTIONS = ("modify", "create", "delete", "verify")
MAX_STEPS = 12
MAX_FILES_PER_STEP = 8
BUILD_FILES = ("pom.xml", "package.json", "requirements.txt", "pyproject.toml", "build.gradle")


# ---------------------------------------------------------------------------------------------- normalise
def _dep_ids(value, index: int) -> list:
    """Accepts [1, 2], ["s1"], "2"; missing means 'after the previous step'. Returns ids like 's1'."""
    if value is None:
        return [f"s{index - 1}"] if index > 1 else []
    items = value if isinstance(value, list) else [value]
    out = []
    for item in items:
        m = re.search(r"\d+", str(item))
        out.append(f"s{m.group()}" if m else str(item))
    return out


def normalize_plan(raw: dict, version: int, generated_by: str) -> dict:
    """Make any plan-shaped dict into the canonical form (ids assigned by position, never trusted from the model)."""
    steps = []
    for i, s in enumerate((raw or {}).get("steps") or [], 1):
        if not isinstance(s, dict):
            continue
        files = s.get("files") if isinstance(s.get("files"), list) else []
        action = str(s.get("action", "modify")).strip().lower()
        steps.append({"id": f"s{i}", "title": str(s.get("title", "")).strip()[:120],
                      "description": str(s.get("description", "")).strip()[:600], "action": action,
                      "files": [str(f).strip().replace("\\", "/") for f in files if str(f).strip()],
                      "depends_on": _dep_ids(s.get("depends_on"), i),
                      "verify": s.get("verify") or None})
    return {"version": version, "generated_by": generated_by, "fallback_reason": (raw or {}).get("fallback_reason", ""),
            "summary": str((raw or {}).get("summary", "")).strip()[:300], "steps": steps,
            "risks": [str(r) for r in ((raw or {}).get("risks") or [])][:8],
            "assumptions": [str(a) for a in ((raw or {}).get("assumptions") or [])][:8],
            "created_at": datetime.now(timezone.utc).isoformat()}


# ---------------------------------------------------------------------------------------------- validate
def _unsafe(path: str) -> str:
    if path.startswith("/") or re.match(r"^[A-Za-z]:", path) or ".." in path.split("/"):
        return "must be a relative path inside the project"
    if is_ignored(path):
        return "is in an ignored or secret location"
    return ""


def validate_plan(plan: dict, all_files: set, commands: set) -> list:
    problems = []
    steps = plan.get("steps") or []
    if not 1 <= len(steps) <= MAX_STEPS:
        problems.append(f"a plan needs 1 to {MAX_STEPS} steps (this one has {len(steps)})")
    seen = set()
    for step in steps:
        sid = step["id"]
        if not step["title"]:
            problems.append(f"{sid}: the title is empty")
        if step["action"] not in ACTIONS:
            problems.append(f"{sid}: action must be one of {', '.join(ACTIONS)}")
            continue
        if len(step["files"]) > MAX_FILES_PER_STEP:
            problems.append(f"{sid}: too many files ({len(step['files'])}); split it into smaller steps")
        for dep in step["depends_on"]:
            if dep not in seen:
                problems.append(f"{sid}: depends on '{dep}', which is not an earlier step")
        if step["action"] == "verify":
            if step.get("verify") not in commands:
                problems.append(f"{sid}: verify must be one of {sorted(commands) or 'no commands are available'}")
        else:
            if not step["files"]:
                problems.append(f"{sid}: a {step['action']} step needs at least one file")
            for f in step["files"]:
                bad = _unsafe(f)
                if bad:
                    problems.append(f"{sid}: '{f}' {bad}")
                elif step["action"] in ("modify", "delete") and f not in all_files:
                    problems.append(f"{sid}: '{f}' does not exist, so it cannot be {step['action']}d")
                elif step["action"] == "create" and f in all_files:
                    problems.append(f"{sid}: '{f}' already exists; use modify instead of create")
        seen.add(sid)
    if steps and all(s["action"] == "verify" for s in steps):
        problems.append("a plan needs at least one step that changes files")
    if steps and steps[0]["action"] == "verify":
        problems.append("the first step cannot be a verify step")
    return problems


def compute_approvals(plan: dict) -> list:
    """Steps a person must explicitly acknowledge before the agent may touch anything."""
    out = []
    for step in plan.get("steps", []):
        files = step.get("files", [])
        if step["action"] == "delete":
            out.append({"step": step["id"], "kind": "delete", "reason": "deletes " + ", ".join(files)})
        build = [f for f in files if f.rsplit("/", 1)[-1] in BUILD_FILES]
        if build and step["action"] != "verify":
            out.append({"step": step["id"], "kind": "dependency", "reason": "changes a build file: " + ", ".join(build)})
        db = [f for f in files if classify(f) in ("entity", "migration")]
        if db and step["action"] != "verify":
            out.append({"step": step["id"], "kind": "database", "reason": "changes the data model: " + ", ".join(db)})
        cfg = [f for f in files if classify(f) == "config" and f.rsplit("/", 1)[-1] not in BUILD_FILES]
        if cfg and step["action"] != "verify":
            out.append({"step": step["id"], "kind": "config", "reason": "changes configuration: " + ", ".join(cfg)})
    return out


def finalize(plan: dict, all_files: set, commands: set) -> dict:
    """Attach the checks every plan carries: problems, approvals needed, tests being added."""
    plan["problems"] = validate_plan(plan, all_files, commands)
    plan["approval_required"] = compute_approvals(plan)
    plan["tests_to_add"] = [f for s in plan["steps"] if s["action"] == "create" for f in s["files"]
                            if classify(f) == "test"]
    plan["commands"] = sorted({s["verify"] for s in plan["steps"] if s["action"] == "verify" and s.get("verify")})
    return plan


# ---------------------------------------------------------------------------------------------- template
def _test_path_for(path: str, profile: dict) -> str | None:
    suffix = (profile.get("java") or {}).get("test_suffix", "Test")
    if path.endswith(".java") and "/src/main/java/" in path:
        return path.replace("/src/main/java/", "/src/test/java/")[:-5] + suffix + ".java"
    return None


def _verify_steps(files: list, profile: dict) -> list:
    """Verification commands for every module the plan touches (compile and test, build and lint)."""
    cmds = profile.get("commands") or {}
    out = []
    for module in profile.get("modules", []):
        d = module["dir"]
        if not any(f == d or f.startswith(d + "/") or d == "" for f in files):
            continue
        for kind in ("compile", "build", "test", "lint"):
            key = f"{module['label']}:{kind}"
            if cmds.get(key, {}).get("available"):
                out.append(key)
    return out[:4]


def template_plan(understanding: dict, impact: dict, profile: dict) -> dict:
    """A sensible plan built only from the impact analysis: layers in dependency order, tests, then verification."""
    modify = [c for c in impact.get("components", []) if c["role"] == "modify"]
    new = impact.get("suggested_new_files", [])
    summary = understanding.get("summary") or understanding.get("command", "")
    goal = "; ".join((understanding.get("acceptance_criteria") or [])[:2])
    groups: dict = {}
    for c in modify:
        groups.setdefault((c["layer"], "modify"), []).append(c["path"])
    for n in new:
        if n["layer"] != "test":
            groups.setdefault((n["layer"], "create"), []).append(n["path"])
    order = lambda key: (LAYER_ORDER.index(key[0]) if key[0] in LAYER_ORDER else 99, key[1] != "create")
    steps = []
    for (layer, action), files in sorted(groups.items(), key=lambda kv: order(kv[0])):
        names = ", ".join(f.rsplit("/", 1)[-1] for f in files[:4])
        verb = "Create" if action == "create" else "Update"
        steps.append({"title": f"{verb} the {layer.replace('-', ' ')} layer", "action": action, "files": files[:8],
                      "description": f"{verb} {names} so the {layer.replace('-', ' ')} part of the task works"
                                     + (f": {goal}" if goal else ".")})
    existing_tests = [c["path"] for c in impact.get("components", []) if c["role"] == "test"]
    if existing_tests and modify:
        steps.append({"title": "Update the existing tests", "action": "modify", "files": existing_tests[:6],
                      "description": "Adjust and extend the tests that cover the changed code."})
    test_files = [n["path"] for n in new if n["layer"] == "test"]
    covered = {r for c in impact.get("components", []) if c["role"] == "test" for r in c["why"].split("; ")}
    for c in modify:
        t = _test_path_for(c["path"], profile)
        if t and f"test for {c['path']}" not in covered and t not in test_files:
            test_files.append(t)
    if test_files:
        steps.append({"title": "Add tests for the new behaviour", "action": "create", "files": test_files[:6],
                      "description": "Write tests for each acceptance criterion, in the style of the existing tests."})
    touched = [f for s in steps for f in s["files"]]
    for key in _verify_steps(touched, profile):
        steps.append({"title": f"Verify: {key}", "action": "verify", "files": [], "verify": key,
                      "description": f"Run {key} and fix anything it reports."})
    return {"summary": summary, "steps": steps, "risks": impact.get("risks", []),
            "assumptions": understanding.get("assumptions", [])}


# ---------------------------------------------------------------------------------------------- render
def plan_to_markdown(plan: dict, understanding: dict | None = None) -> str:
    u = understanding or {}
    out = [f"# Implementation plan (version {plan['version']}, {plan['generated_by']})", ""]
    if plan.get("fallback_reason"):
        out += [f"> Template plan: {plan['fallback_reason']}", ""]
    if plan.get("summary"):
        out += [f"**Summary:** {plan['summary']}", ""]
    if u.get("acceptance_criteria"):
        out += ["## Acceptance criteria"] + [f"- {c}" for c in u["acceptance_criteria"]] + [""]
    out.append("## Steps")
    for s in plan["steps"]:
        deps = f" (after {', '.join(s['depends_on'])})" if s["depends_on"] else ""
        out.append(f"{s['id'][1:]}. **{s['title']}** `{s['action']}`{deps}")
        if s["description"]:
            out.append(f"   {s['description']}")
        for f in s["files"]:
            out.append(f"   - `{f}`")
        if s.get("verify"):
            out.append(f"   - run `{s['verify']}`")
    for title, key in (("Approval required", "approval_required"), ("Risks", "risks"), ("Assumptions", "assumptions"),
                       ("Problems to fix before approval", "problems")):
        items = plan.get(key) or []
        if items:
            out += ["", f"## {title}"]
            out += [f"- {i['step']}: {i['reason']}" if isinstance(i, dict) else f"- {i}" for i in items]
    return "\n".join(out) + "\n"