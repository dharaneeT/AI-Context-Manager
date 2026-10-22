"""Stage 100 (implementation phase): make the private copy of the project where the agent will work."""
from __future__ import annotations

from app.context.files import list_project_files, map_root
from app.context.git import repo_state
from app.sandbox.workspace import create_workspace

NAME = "Workspace"
ORDER = 100


def run(ctx) -> str:
    run = ctx.require_approved()
    s = ctx.services
    source = map_root(s.backend.project(run["project_id"])["rootPath"], s.settings.path_map)
    files = list_project_files(source)
    meta = create_workspace(s.settings, run["id"], source, files, repo_state(source))
    ctx.save(workspace=meta, terminal_status="APPROVED", terminal_label="Workspace ready")
    note = f"{meta['file_count']} files copied" + (f", linked {', '.join(meta['linked'])}" if meta["linked"] else "")
    return note