"""Stage 110 (implementation phase): carry out the approved plan, step by step, inside the workspace."""
from __future__ import annotations

from app.implement.executor import implement_plan
from app.orchestrator.stages import StageStop
from app.sandbox import workspace as ws

NAME = "Implement"
ORDER = 110


def run(ctx) -> str:
    run = ctx.require_approved()
    tree = ws.require_workspace(run)
    record = implement_plan(ctx, tree, run["plan"])
    done = [x for x in record["steps"] if x["status"] == "DONE"]
    if record["outcome"] != "DONE":
        failed = next(x for x in record["steps"] if x["status"] != "DONE")
        ctx.save(error=f"Step {failed['id']} ({failed['title']}): {failed['problem']}"[:500])
        # park the run: the stage stays "not finished", so running the implementation again retries only this step
        raise StageStop("PARTIAL", f"Step {failed['id']} could not be done",
                        f"{len(done)} step(s) done, then {failed['id']} failed: {failed['problem']}"[:300])
    ctx.save(terminal_status="IMPLEMENTED", terminal_label="Implemented (not verified yet)", error=None)
    total = ws.changed_files(ws.require_workspace(ctx.run), ctx.run["workspace"]["baseline"])
    return f"{len(done)} step(s) done; {len(total)} file(s) changed"
