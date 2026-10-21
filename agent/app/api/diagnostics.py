from fastapi import APIRouter

from app.deps import get_services

PUBLIC = True
router = APIRouter(tags=["health"])


@router.get("/health/deep")
def health_deep() -> dict:
    """Checks the things the agent depends on: the Spring backend and the LLM. Reports problems, never raises."""
    s = get_services()
    backend_ok, detail = True, ""
    try:
        s.backend.health()
    except Exception as e:
        backend_ok, detail = False, str(e)
    try:
        llm = s.llm.status()
    except Exception as e:
        llm = {"reachable": False, "detail": str(e)}
    return {"agent": "UP", "backend": {"reachable": backend_ok, "detail": detail, "url": s.settings.backend_url},
            "llm": llm}