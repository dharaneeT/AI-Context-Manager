"""What the approved plan allows the agent to touch."""
from __future__ import annotations


def approved_files(run: dict, actions: tuple = ("modify", "create")) -> set:
    """Every file named by a step of the CURRENT plan with one of `actions`. Anything else is out of scope."""
    plan = run.get("plan") or {}
    return {f for s in plan.get("steps", []) if s["action"] in actions for f in s["files"]}


def step_by_id(run: dict, step_id: str) -> dict | None:
    return next((s for s in (run.get("plan") or {}).get("steps", []) if s["id"] == step_id), None)
