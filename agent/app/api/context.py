from fastapi import APIRouter

from app.context.expander import from_search
from app.context.files import map_root, read_file
from app.deps import get_services

router = APIRouter(prefix="/context", tags=["context"])


@router.post("/search")
def search(project_id: int, q: str, limit: int = 25) -> dict:
    """Search adapter: runs the existing ranking + hybrid search and returns one scored entry per FILE."""
    backend = get_services().backend
    ranking = backend.rank(project_id, q, min(max(limit, 1), 50))
    hybrid = backend.hybrid_search(project_id, q, 15)
    cands = sorted(from_search(ranking, hybrid).values(), key=lambda c: -c.score)
    return {"keywords": ranking.get("keywords", []), "semanticUsed": ranking.get("semanticUsed"),
            "files": [{"path": c.path, "score": round(c.score, 3), "reasons": c.reasons,
                       "matchedRanges": c.ranges} for c in cands]}


@router.get("/file")
def read_one_file(project_id: int, path: str) -> dict:
    """Read ONE COMPLETE file (redacted, size-capped). This is what makes the agent see whole files, not chunks."""
    s = get_services()
    root = map_root(s.backend.project(project_id)["rootPath"], s.settings.path_map)
    c = read_file(root, path, s.settings.max_file_bytes)
    return {"path": c.path, "language": c.language, "lineCount": c.line_count, "truncated": c.truncated,
            "redactions": c.redactions, "skipped": c.skipped or None, "text": c.text}