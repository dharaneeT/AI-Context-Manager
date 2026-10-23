from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.core.errors import AgentError
from app.deps import get_services
from app.orchestrator.stages import StageContext
from app.sandbox import workspace as ws
from app.verify.runner import commands_from_plan, describe, verify_once

router = APIRouter(prefix="/runs", tags=["verify"])


class VerifyRequest(BaseModel):
    commands: list[str] | None = Field(default=None, description="Profile command names; default: the plan's own")


@router.post("/{run_id}/workspace/verify")
def verify(run_id: int, body: VerifyRequest) -> dict:
    """Run the verification commands now (manual re-check). The result is stored as an extra attempt."""
    s = get_services()
    run = s.store.get(run_id)
    if run["status"] in ("QUEUED", "RUNNING"):
        raise AgentError("The agent is working on this run; wait until it is idle", 409)
    tree = ws.require_workspace(run)
    commands = body.commands or commands_from_plan(run.get("plan") or {})
    if not commands:
        raise AgentError("There is nothing to run: the plan has no verification commands", 409)
    record = run.get("verification") or {"commands": commands, "attempts": [], "ok": False}
    attempt = verify_once(StageContext(s, run_id), tree, run.get("profile") or {}, commands, len(record["attempts"]) + 1)
    record["attempts"].append(attempt)
    record["ok"] = attempt["ok"]
    s.store.update(run_id, verification=record)
    return {"ok": attempt["ok"], "summary": describe(attempt), "attempt": attempt}
