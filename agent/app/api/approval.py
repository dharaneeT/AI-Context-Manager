from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.deps import get_services
from app.planner import approval


class PlanStep(BaseModel):
    title: str = Field(max_length=120)
    description: str = Field(default="", max_length=600)
    action: str = "modify"
    files: list[str] = Field(default_factory=list)
    depends_on: list[str] | None = None
    verify: str | None = None


class PlanEdit(BaseModel):
    steps: list[PlanStep]
    summary: str | None = None
    edited_by: str = "local-user"


class Approve(BaseModel):
    version: int = Field(ge=1)
    acknowledged: list[str] = Field(default_factory=list)
    approved_by: str = "local-user"
    note: str = ""


class Reject(BaseModel):
    reason: str = Field(min_length=1, max_length=500)
    rejected_by: str = "local-user"


router = APIRouter(prefix="/runs", tags=["approval"])


@router.put("/{run_id}/plan")
def edit_plan(run_id: int, body: PlanEdit) -> dict:
    """Save the person's edited steps as a new plan version (validated by the same rules as a model's plan)."""
    steps = [s.model_dump() for s in body.steps]
    return approval.edit_plan(get_services(), run_id, steps, body.summary, body.edited_by)


@router.post("/{run_id}/approve")
def approve(run_id: int, body: Approve) -> dict:
    return approval.approve(get_services(), run_id, body.version, body.acknowledged, body.approved_by, body.note)


@router.post("/{run_id}/reject")
def reject(run_id: int, body: Reject) -> dict:
    return approval.reject(get_services(), run_id, body.reason, body.rejected_by)