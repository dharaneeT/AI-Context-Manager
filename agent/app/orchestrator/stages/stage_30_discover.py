"""Stage 30: find the relevant files (search + graph + tests + config) and save them with their scores."""
from __future__ import annotations

from app.context import pipeline
from app.context.understand import Understanding

NAME = "Discover"
ORDER = 30
SAVED_CANDIDATES = 40


def run(ctx) -> str:
    run = ctx.run
    s = ctx.services
    u = Understanding.from_dict(run["understanding"])
    found = pipeline.discover(s.settings, s.backend, run["project_id"], u.search_text(), u)
    ctx.save(candidates=[c.public() for c in found.candidates[:SAVED_CANDIDATES]],
             repo=found.repo, discovery_notes=found.notes, project_file_count=len(found.all_files))
    return f"{len(found.candidates)} candidate files out of {len(found.all_files)}"