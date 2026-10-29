"""Stage 130 (implementation phase): if verification failed, try to repair it within strict limits."""
from __future__ import annotations

from app.orchestrator.stages import StageStop
from app.repair.loop import repair_loop
from app.sandbox import workspace as ws

NAME = "Repair"
ORDER = 130


def run(ctx) -> str:
    run = ctx.require_approved()
    verification = run.get("verification")
    if not verification or (run.get("implementation") or {}).get("outcome") != "DONE":
        return "skipped: nothing was verified"
    if verification["attempts"][-1]["ok"]:
        n = len([a for a in verification["attempts"] if a.get("after_repair")])
        ctx.save(terminal_status="COMPLETE", terminal_label="Complete: implemented and verified", error=None)
        return "nothing to repair" if not n else f"fixed after {n} repair attempt(s)"
    record = repair_loop(ctx, ws.require_workspace(run), run)
    ctx.save(repair=record)
    attempts = len(record["attempts"])
    if record["ok"]:
        ctx.save(terminal_status="COMPLETE", terminal_label=f"Complete: verified after {attempts} repair attempt(s)", error=None)
        return f"fixed after {attempts} repair attempt(s)"
    ctx.save(error=("; ".join(record["diagnostics"][:2]))[:500])
    # parked, not finished: starting the implementation again gives the repair loop a fresh, bounded round
    raise StageStop("PARTIAL", "Partly done: see the diagnostics",
                    f"not fixed after {attempts} attempt(s): {record['stopped']}"[:300])