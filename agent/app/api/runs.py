from fastapi import APIRouter
from fastapi.responses import PlainTextResponse

from app.deps import get_services
from app.schemas import AnswersRequest, RunCreate

router = APIRouter(prefix="/runs", tags=["runs"])


@router.post("", status_code=202)
def create_run(body: RunCreate) -> dict:
    """Spring Boot calls this when a user submits an agent task. Returns at once; the work runs in the background."""
    s = get_services()
    return s.orchestrator.submit(body.task_id, body.project_id, body.command.strip(), body.max_tokens)


@router.get("")
def list_runs(limit: int = 20) -> list:
    return get_services().store.list(min(max(limit, 1), 100))


@router.get("/{run_id}")
def get_run(run_id: int) -> dict:
    return get_services().store.get(run_id)


@router.post("/{run_id}/answers", status_code=202)
def answer_questions(run_id: int, body: AnswersRequest) -> dict:
    """The user answered (or skipped) the agent's clarifying questions: the run continues."""
    return get_services().orchestrator.provide_answers(run_id, body.answers)


@router.post("/{run_id}/cancel")
def cancel_run(run_id: int) -> dict:
    return get_services().orchestrator.cancel(run_id)


@router.get("/{run_id}/pack.md", response_class=PlainTextResponse)
def pack_markdown(run_id: int):
    return PlainTextResponse(get_services().store.pack_markdown(run_id), media_type="text/markdown; charset=utf-8")