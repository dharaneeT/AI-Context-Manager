"""Stage 60: write the implementation plan, then park the run until a person approves it."""
from __future__ import annotations

from app.context.files import list_project_files, map_root
from app.orchestrator.stages import StageStop
from app.planner.plan import plan_to_markdown
from app.planner.plan_llm import generate_plan

NAME = "Plan"
ORDER = 60


def run(ctx) -> str:
    run = ctx.run
    s = ctx.services
    root = map_root(s.backend.project(run["project_id"])["rootPath"], s.settings.path_map)
    plan = generate_plan(s.settings, s.llm, run, list_project_files(root), version=1)
    s.store.append(run["id"], "plans", plan)
    ctx.save(plan=plan)
    s.store.save_text(run["id"], "plan.md", plan_to_markdown(plan, run.get("understanding")))
    note = f"{len(plan['steps'])} steps ({plan['generated_by']}), {len(plan['approval_required'])} need approval"
    if plan["problems"]:
        note += f", {len(plan['problems'])} problem(s) to fix"
    raise StageStop("AWAITING_APPROVAL", "Waiting for plan approval", note, step_done=True)