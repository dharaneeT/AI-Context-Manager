from fastapi import APIRouter

from app.context.profile import conventions_text, get_profile
from app.deps import get_services

router = APIRouter(prefix="/projects", tags=["profile"])


@router.get("/{project_id}/profile")
def profile(project_id: int, refresh: bool = False) -> dict:
    """How this project is built and written. Add ?refresh=true to ignore the cache."""
    p = get_profile(get_services(), project_id, force=refresh)
    return {"profile": p, "conventions": conventions_text(p, 1500)}