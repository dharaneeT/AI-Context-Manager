
"""Day 2 worker: Understand stage plus a placeholder context pack."""
from __future__ import annotations

import logging
from concurrent.futures import ThreadPoolExecutor

from app.context.understand import understand
from app.core.errors import AgentError
from app.orchestrator.store import ACTIVE, now

log = logging.getLogger("agent.runner")
MAX_ACTIVE_RUNS = 20


class Orchestrator:
    def __init__(self, services):
        self.services = services
        self.pool = ThreadPoolExecutor(
            max_workers=1,
            thread_name_prefix="agent-run",
        )

    @property
    def store(self):
        return self.services.store

    def submit(
        self,
        run_id: int,
        project_id: int,
        command: str,
        max_tokens,
    ) -> dict:
        active = [
            run for run in self.store.list(200)
            if run["status"] in ACTIVE
        ]

        if len(active) >= MAX_ACTIVE_RUNS:
            raise AgentError(
                "Too many runs are queued. Try again in a moment.",
                409,
            )

        run = self.store.create(
            run_id, project_id, command, max_tokens
        )
        self.pool.submit(self._execute, run_id)
        return run

    def cancel(self, run_id: int) -> dict:
        run = self.store.get(run_id)

        if run["status"] not in ACTIVE:
            return run

        self.store.update(run_id, cancel_requested=True)

        if run["status"] == "QUEUED":
            self.store.update(
                run_id,
                status="CANCELLED",
                stage="Cancelled",
                finished_at=now(),
            )

        return self.store.get(run_id)

    def _execute(self, run_id: int) -> None:
        run = self.store.get(run_id)

        if run["status"] != "QUEUED":
            return

        self.store.update(
            run_id,
            status="RUNNING",
            started_at=now(),
        )

        try:
            self.store.begin_step(run_id, "Understand")

            result = understand(run["command"])
            self.store.update(
                run_id,
                understanding=result.as_dict(),
            )

            self.store.end_step(
                run_id,
                "DONE",
                f"areas={result.areas or 'none detected'}, "
                f"{len(result.keywords)} keywords",
            )

            self.store.save_pack_markdown(
                run_id,
                f"# Placeholder pack (Day 2)\n\n"
                f"**Task:** {run['command']}\n\n"
                f"Detected areas: {result.areas}\n\n"
                "File discovery arrives on Day 4 and 5.\n",
            )

            self.store.update(
                run_id,
                status="CONTEXT_READY",
                stage="Understood (discovery not built yet)",
                finished_at=now(),
            )

        except Exception as exc:
            log.exception("Run %s crashed", run_id)
            self.store.update(
                run_id,
                status="FAILED",
                stage="Failed",
                error=f"{type(exc).__name__}: {exc}"[:500],
                finished_at=now(),
            )
