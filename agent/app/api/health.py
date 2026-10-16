import platform
from datetime import datetime, timezone

from fastapi import APIRouter

from app import __version__

PUBLIC = True  # no secret needed: lets you check that the service is alive
router = APIRouter(tags=["health"])


@router.get("/health")
def health() -> dict:
    return {"status": "UP", "service": "contextlayer-agent", "version": __version__,
            "python": platform.python_version(), "time": datetime.now(timezone.utc).isoformat()}