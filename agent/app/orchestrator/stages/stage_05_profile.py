"""Stage 05: detect how the project is built, tested and written (cached until something relevant changes)."""
from __future__ import annotations

from app.context.profile import available_commands, get_profile

NAME = "Profile"
ORDER = 5


def run(ctx) -> str:
    profile = get_profile(ctx.services, ctx.run["project_id"])
    ctx.save(profile=profile)
    kinds = ", ".join(f"{m['label']} ({m['kind']})" for m in profile["modules"]) or "no build files found"
    return f"{kinds}; {len(available_commands(profile))} verification commands" + (" [cached]" if profile["cached"] else "")