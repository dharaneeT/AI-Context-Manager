"""The approval gate: a person edits, approves or rejects the plan. Nothing is implemented without it."""
from __future__ import annotations

from app.context.files import list_project_files, map_root
from app.context.profile import available_commands
from app.core.errors import AgentError
from app.orchestrator.store import now
from app.planner.plan import finalize, normalize_plan, plan_to_markdown


def _awaiting(run: dict) -> None:
    if run["status"] != "AWAITING_APPROVAL":
        raise AgentError(f"Run {run['id']} is {run['status']}: only a run awaiting approval can be changed", 409)


def _project_files(services, run: dict) -> set:
    root = map_root(services.backend.project(run["project_id"])["rootPath"], services.settings.path_map)
    return set(list_project_files(root))


def edit_plan(services, run_id: int, steps: list, summary: str | None, edited_by: str) -> dict:
    """The person's edited steps become a NEW plan version (the old one is kept). The same rules apply as to a model."""
    run = services.store.get(run_id)
    _awaiting(run)
    current = run["plan"]
    raw = {"summary": summary if summary is not None else current["summary"], "steps": steps,
           "risks": current.get("risks", []), "assumptions": current.get("assumptions", [])}
    plan = normalize_plan(raw, current["version"] + 1, f"edited by {edited_by}")
    plan = finalize(plan, _project_files(services, run), set(available_commands(run.get("profile") or {})))
    services.store.append(run_id, "plans", plan)
    services.store.update(run_id, plan=plan)
    services.store.save_text(run_id, "plan.md", plan_to_markdown(plan, run.get("understanding")))
    return plan


def approve(services, run_id: int, version: int, acknowledged: list, by: str, note: str = "") -> dict:
    run = services.store.get(run_id)
    _awaiting(run)
    plan = run["plan"]
    if version != plan["version"]:
        raise AgentError(f"You approved version {version}, but the current plan is version {plan['version']}. "
                         "Review the latest version first.", 409)
    if plan["problems"]:
        raise AgentError("The plan still has problems: " + "; ".join(plan["problems"][:3]), 409)
    needed = {a["step"] for a in plan["approval_required"]}
    missing = sorted(needed - set(acknowledged or []))
    if missing:
        raise AgentError(f"These risky steps must be acknowledged first: {', '.join(missing)}", 409)
    record = {"at": now(), "by": by, "decision": "APPROVED", "version": version, "acknowledged": sorted(needed),
              "note": note}
    services.store.append(run_id, "approvals", record)
    services.store.update(run_id, status="APPROVED", stage="Plan approved (implementation starts on Day 11)",
                          finished_at=now())
    return services.store.get(run_id)


def reject(services, run_id: int, reason: str, by: str) -> dict:
    run = services.store.get(run_id)
    _awaiting(run)
    if not reason.strip():
        raise AgentError("Say why the plan is rejected, so the next attempt can be better", 400)
    services.store.append(run_id, "approvals", {"at": now(), "by": by, "decision": "REJECTED",
                                                "version": run["plan"]["version"], "acknowledged": [], "note": reason})
    services.store.update(run_id, status="REJECTED", stage="Plan rejected", error=reason.strip()[:500],
                          finished_at=now())
    return services.store.get(run_id)