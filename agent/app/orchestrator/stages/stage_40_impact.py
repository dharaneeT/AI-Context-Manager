"""Stage 40: work out which layers and files the task touches, and whether to extend existing code or create new."""
from __future__ import annotations

from app.context.files import list_project_files, map_root
from app.planner.impact import classify, compute_impact

NAME = "Impact"
ORDER = 40
SYMBOL_LOOKUPS = 12  # at most this many files are asked for their annotations (@Entity, @Service, ...)


def run(ctx) -> str:
    run = ctx.run
    s = ctx.services
    root = map_root(s.backend.project(run["project_id"])["rootPath"], s.settings.path_map)
    all_files = list_project_files(root)
    candidates = run["candidates"] or []

    annotations, dependents, notes = {}, {}, []
    java = [c["path"] for c in candidates if c["path"].endswith(".java") and classify(c["path"]) == "java-other"]
    for path in java[:SYMBOL_LOOKUPS]:
        try:
            symbols = s.backend.symbols(run["project_id"], path)
            annotations[path] = " ".join(str(x.get("annotations") or "") for x in symbols)
        except Exception as e:  # symbols are a refinement, not a requirement
            notes.append(f"Could not read symbols for {path}: {e}")
            break
    for c in candidates[:6]:
        try:
            dependents[c["path"]] = len(s.backend.dependents(run["project_id"], c["path"]))
        except Exception:
            break
    impact = compute_impact(run["command"], run["understanding"] or {}, run.get("profile") or {}, candidates,
                            annotations, dependents, all_files)
    impact["notes"] = notes
    ctx.save(impact=impact)
    modify = sum(1 for c in impact["components"] if c["role"] == "modify")
    return f"{modify} files to modify across {len(impact['layers_touched'])} layers; {len(impact['risks'])} risks"