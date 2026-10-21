"""Stage 10: turn the command into a summary, acceptance criteria and (if truly needed) clarifying questions."""
from __future__ import annotations

from app.context.understand import Understanding
from app.orchestrator.stages import StageStop
from app.planner.understanding import analyze

NAME = "Understand"
ORDER = 10


def run(ctx) -> str:
    run = ctx.run
    answers = run.get("answers")  # None = the user has not answered; {} = the user skipped the questions
    previous = run.get("understanding")
    if previous and answers is None and previous.get("needs_clarification") and previous.get("questions"):
        raise StageStop("NEEDS_INPUT", "Waiting for your answers", "still waiting for answers")
    u = analyze(ctx.services, run["command"], run.get("profile"), answers, (previous or {}).get("questions"))
    if answers is not None:
        u.answers = answers
    ctx.save(understanding=u.as_dict())
    if answers is None and u.needs_clarification and u.questions:
        raise StageStop("NEEDS_INPUT", "Waiting for your answers", f"{len(u.questions)} question(s) for you")
    return f"{u.source}: {len(u.acceptance_criteria)} criteria, areas={u.areas or 'none detected'}"