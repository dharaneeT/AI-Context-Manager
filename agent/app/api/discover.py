from fastapi import APIRouter

from app.context import pipeline
from app.deps import get_services
from app.schemas import DiscoverRequest

router = APIRouter(prefix="/context", tags=["context"])


@router.post("/discover")
def discover(body: DiscoverRequest) -> dict:
    """Synchronous preview: understand -> discover -> pack right now, without creating a run."""
    s = get_services()
    u, found, pack = pipeline.run_all(s.settings, s.backend, body.project_id, body.command.strip(), body.max_tokens)
    out = {"understanding": u.as_dict(), "pack": pack.public()}
    if body.include_markdown:
        out["markdown"] = pack.markdown
    return out