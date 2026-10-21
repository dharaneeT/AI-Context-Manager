from fastapi import APIRouter
from fastapi.responses import PlainTextResponse

from app.core.errors import NotFound
from app.deps import get_services

router = APIRouter(prefix="/runs", tags=["plan"])


@router.get("/{run_id}/plan")
def get_plan(run_id: int) -> dict:
    s = get_services()
    run = s.store.get(run_id)
    if not run.get("plan"):
        raise NotFound(f"Run {run_id} has no plan yet (status {run['status']})")
    return {"plan": run["plan"], "versions": [p["version"] for p in run["plans"]],
            "markdown": s.store.read_text(run_id, "plan.md")}


@router.get("/{run_id}/plan.md", response_class=PlainTextResponse)
def plan_markdown(run_id: int):
    return PlainTextResponse(get_services().store.read_text(run_id, "plan.md"), media_type="text/markdown; charset=utf-8")