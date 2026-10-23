from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.core.errors import AgentError
from app.deps import get_services
from app.editing.engine import apply_step
from app.editing.parser import parse_edits
from app.editing.scope import approved_files
from app.orchestrator.store import now
from app.sandbox import workspace as ws

router = APIRouter(prefix="/runs", tags=["edits"])


class EditRequest(BaseModel):
    text: str = Field(min_length=10, max_length=100_000, description="SEARCH/REPLACE blocks, as the model writes them")
    label: str = Field(default="manual edit", max_length=100)


class RollbackRequest(BaseModel):
    to: str = Field(default="baseline", description="'baseline', or a checkpoint id from /changes")


def _idle_approved_run(run_id: int) -> dict:
    run = get_services().store.get(run_id)
    if run["status"] in ("QUEUED", "RUNNING"):
        raise AgentError("The agent is working on this run; wait until it is idle", 409)
    approvals = run.get("approvals") or []
    if not approvals or approvals[-1].get("decision") != "APPROVED":
        raise AgentError("The plan has not been approved: nothing may be changed without approval", 409)
    return run


@router.post("/{run_id}/workspace/edits")
def apply(run_id: int, body: EditRequest) -> dict:
    """Apply SEARCH/REPLACE blocks to the workspace. Only files named in the approved plan may be touched."""
    s = get_services()
    run = _idle_approved_run(run_id)
    tree = ws.require_workspace(run)
    edits, problems = parse_edits(body.text)
    if problems:
        raise AgentError("Could not read the edits: " + "; ".join(problems), 422)
    step = apply_step(tree, edits, approved_files(run), s.settings.max_file_bytes, body.label)
    s.store.append(run_id, "changes", {**step, "at": now()})
    return step


@router.get("/{run_id}/changes")
def changes(run_id: int) -> dict:
    run = get_services().store.get(run_id)
    summary = ws.changed_files(ws.require_workspace(run), run["workspace"]["baseline"]) if run.get("workspace") else []
    return {"checkpoints": run["changes"], "total": summary}


@router.post("/{run_id}/workspace/rollback")
def rollback(run_id: int, body: RollbackRequest) -> dict:
    s = get_services()
    run = _idle_approved_run(run_id)
    tree = ws.require_workspace(run)
    known = {c["checkpoint"] for c in run["changes"]}
    target = run["workspace"]["baseline"] if body.to == "baseline" else body.to
    if body.to != "baseline" and target not in known:
        raise AgentError(f"Unknown checkpoint '{body.to}'", 404)
    ws.rollback_to(tree, target)
    kept = [] if body.to == "baseline" else run["changes"][: [c["checkpoint"] for c in run["changes"]].index(target) + 1]
    s.store.update(run_id, changes=kept)
    return {"rolled_back_to": body.to, "remaining": ws.changed_files(tree, run["workspace"]["baseline"])}
