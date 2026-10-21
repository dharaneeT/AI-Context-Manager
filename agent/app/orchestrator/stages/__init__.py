"""Stages are plug-ins. Any module here named stage_<order>_<name>.py is run in ORDER, so a new day adds a file
and never edits the runner.

A stage module defines:
    NAME  = "Understand"          shown in the UI timeline
    ORDER = 10                    lower runs first
    def run(ctx) -> str           does the work, saves results with ctx.save(...), returns a one-line summary

A stage may raise StageStop to park the run until a person acts (answers, approval).
"""
from __future__ import annotations

import importlib
import pkgutil
from dataclasses import dataclass


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


def load_stages() -> list:
    stages = []
    for info in pkgutil.iter_modules(__path__):
        if info.name.startswith("stage_"):
            module = importlib.import_module(f"{__name__}.{info.name}")
            stages.append(module)
    return sorted(stages, key=lambda m: m.ORDER)