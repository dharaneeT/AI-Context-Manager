"""Stage 120 (implementation phase): build and test what the agent changed, and record the evidence."""
from __future__ import annotations

from app.sandbox import workspace as ws
from app.verify.runner import commands_from_plan, describe, verify_once

NAME = "Verify"
ORDER = 120


def run(ctx) -> str:
    run = ctx.require_approved()
    implementation = run.get("implementation") or {}
    if implementation.get("outcome") != "DONE":
        return "skipped: the implementation is not complete"
    tree = ws.require_workspace(run)
    commands = commands_from_plan(run["plan"])
    if not commands:
        ctx.save(terminal_status="IMPLEMENTED", terminal_label="Implemented, but the plan has no verification commands")
        return "no verification commands in the plan: the change is NOT verified"
    attempt = verify_once(ctx, tree, run.get("profile") or {}, commands, 1)
    ctx.save(verification={"commands": commands, "attempts": [attempt], "ok": attempt["ok"]},
             terminal_status="VERIFIED" if attempt["ok"] else "VERIFY_FAILED",
             terminal_label="Verified: build and tests pass" if attempt["ok"] else "Verification failed",
             error=None if attempt["ok"] else describe(attempt)[:500])
    return describe(attempt)[:300]