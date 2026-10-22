from fastapi import APIRouter
from fastapi.responses import PlainTextResponse
from pydantic import BaseModel, Field

from app.context.files import list_project_files, map_root
from app.context.git import repo_state
from app.core.errors import AgentError
from app.deps import get_services
from app.sandbox import workspace as ws
from app.sandbox.runner import run_command

router = APIRouter(prefix="/runs", tags=["workspace"])


class CommandRequest(BaseModel):
    command: str = Field(min_length=3, max_length=60, description="A command name from the profile, e.g. backend:compile")
    timeout_seconds: int | None = Field(default=None, ge=5, le=3600)


def _run(run_id: int) -> dict:
    return get_services().store.get(run_id)


@router.post("/{run_id}/implement", status_code=202)
def implement(run_id: int) -> dict:
    """Continue an APPROVED run into the implementation phase."""
    return get_services().orchestrator.start_implementation(run_id)


@router.post("/{run_id}/workspace", status_code=201)
def create_workspace(run_id: int) -> dict:
    """Create (or recreate) the private copy of the project for this run."""
    s = get_services()
    run = _run(run_id)
    if run["status"] in ("QUEUED", "RUNNING"):
        raise AgentError("Wait until the run is idle before recreating its workspace", 409)
    source = map_root(s.backend.project(run["project_id"])["rootPath"], s.settings.path_map)
    meta = ws.create_workspace(s.settings, run_id, source, list_project_files(source), repo_state(source))
    s.store.update(run_id, workspace=meta)
    return meta


@router.get("/{run_id}/workspace")
def get_workspace(run_id: int) -> dict:
    run = _run(run_id)
    tree = ws.require_workspace(run)
    return {"workspace": run["workspace"], "changes": ws.changed_files(tree, run["workspace"]["baseline"])}


@router.post("/{run_id}/workspace/commands")
def run_workspace_command(run_id: int, body: CommandRequest) -> dict:
    """Run one NAMED command from the project profile (never free text) inside the workspace."""
    s = get_services()
    run = _run(run_id)
    tree = ws.require_workspace(run)
    return run_command(s.settings, tree, run.get("profile") or {}, body.command, body.timeout_seconds)


@router.get("/{run_id}/diff", response_class=PlainTextResponse)
def diff(run_id: int):
    run = _run(run_id)
    return PlainTextResponse(ws.diff_text(ws.require_workspace(run), run["workspace"]["baseline"]),
                             media_type="text/plain; charset=utf-8")


@router.delete("/{run_id}/workspace")
def delete_workspace(run_id: int) -> dict:
    s = get_services()
    _run(run_id)
    ws.destroy_workspace(s.settings, run_id, (_run(run_id).get("workspace") or {}).get("linked", []))
    s.store.update(run_id, workspace=None)
    return {"deleted": True}
