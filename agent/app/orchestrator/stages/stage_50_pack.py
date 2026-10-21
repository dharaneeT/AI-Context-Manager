"""Stage 50: build the token-budgeted context pack from the saved candidates."""
from __future__ import annotations

from app.context.expander import Candidate
from app.context.files import map_root
from app.context.pack import build_pack

NAME = "Pack"
ORDER = 50


def run(ctx) -> str:
    run = ctx.run
    s = ctx.services
    root = map_root(s.backend.project(run["project_id"])["rootPath"], s.settings.path_map)
    candidates = [Candidate.from_public(c) for c in run["candidates"] or []]
    budget = run["max_tokens"] or s.settings.pack_default_tokens
    pack = build_pack(root, candidates, run["command"], budget, s.settings.max_file_bytes,
                      run.get("repo") or {}, run.get("discovery_notes") or [])
    s.store.save_pack_markdown(run["id"], pack.markdown)
    ctx.save(pack=pack.public())
    st = pack.stats()
    return f"{st['fullFiles']} full, {st['windowedFiles']} excerpts, {st['pathOnlyFiles']} paths, ~{st['packTokens']} tokens"