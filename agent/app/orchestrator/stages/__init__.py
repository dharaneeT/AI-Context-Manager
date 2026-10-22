"""Stages are plug-ins. Any module here named stage_<order>_<name>.py is run in ORDER, so a new day adds a file
and never edits the runner.

A stage module defines:
    NAME  = "Understand"          shown in the UI timeline
    ORDER = 10                    lower runs first
    def run(ctx) -> str           does the work, saves results with ctx.save(...), returns a one-line summary

A stage may raise StageStop to park the run until a person acts (answers, approval).
Stages with ORDER 100 and above are the IMPLEMENTATION phase: they only run after a person approved the plan.
A stage can set how the run ends with ctx.save(terminal_status="...", terminal_label="...").
"""
from __future__ import annotations

import importlib
import pkgutil
from dataclasses import dataclass

from app.core.errors import AgentError


class Cancelled(Exception):
    """The user cancelled the run."""


class StageStop(Exception):
    """Park the run. `step_done=False` means the stage runs again when the run resumes."""

    def __init__(self, status: str, label: str, summary: str = "", step_done: bool = False):
        super().__init__(summary or label)
        self.status, self.label, self.summary, self.step_done = status, label, summary, step_done


@dataclass
class StageContext:
    services: object
    run_id: int

    @property
    def run(self) -> dict:
        """The latest saved state of the run (read fresh each time, so stages never see stale data)."""
        return self.services.store.get(self.run_id)

    @property
    def settings(self):
        return self.services.settings

    def save(self, **fields) -> None:
        self.services.store.update(self.run_id, **fields)

    def check_cancel(self) -> None:
        """Long stages call this between steps so Cancel takes effect quickly."""
        if self.run["cancel_requested"]:
            raise Cancelled()

    def require_approved(self) -> dict:
        """The approval gate for the implementation phase: the CURRENT plan version must be approved."""
        run = self.run
        approvals = run.get("approvals") or []
        plan = run.get("plan") or {}
        last = approvals[-1] if approvals else {}
        if last.get("decision") != "APPROVED" or last.get("version") != plan.get("version"):
            raise AgentError("The plan has not been approved: nothing may be changed without approval", 409)
        return run


def load_stages() -> list:
    stages = []
    for info in pkgutil.iter_modules(__path__):
        if info.name.startswith("stage_"):
            module = importlib.import_module(f"{__name__}.{info.name}")
            stages.append(module)
    return sorted(stages, key=lambda m: m.ORDER)
