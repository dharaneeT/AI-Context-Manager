"""Runs a task through its stages on one background worker (an 8 GB PC cannot do two LLM jobs at once).

The stages themselves live in app/orchestrator/stages/. A run can park (NEEDS_INPUT, AWAITING_APPROVAL) and later
resume: stages that already finished are skipped, the rest run again from saved data."""
from __future__ import annotations

import logging
from concurrent.futures import ThreadPoolExecutor

from app.core.errors import AgentError
from app.orchestrator.stages import StageContext, StageStop, load_stages
from app.orchestrator.store import ACTIVE, WAITING, now

log = logging.getLogger("agent.runner")
MAX_ACTIVE_RUNS = 20


class Cancelled(Exception):
    pass


class Orchestrator:
    def __init__(self, services):
        self.services = services
        self.pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="agent-run")

    @property
    def store(self):
        return self.services.store

    # ---- public actions ---------------------------------------------------------------------
    def submit(self, run_id: int, project_id: int, command: str, max_tokens) -> dict:
        active = [r for r in self.store.list(200) if r["status"] in ACTIVE]
        if len(active) >= MAX_ACTIVE_RUNS:
            raise AgentError("Too many runs are queued. Try again in a moment.", 409)
        run = self.store.create(run_id, project_id, command, max_tokens)
        self.pool.submit(self._execute, run_id)
        return run

    def resume(self, run_id: int) -> dict:
        """Queue a parked run again; finished stages are skipped."""
        self.store.update(run_id, status="QUEUED", stage="Queued", error=None, finished_at=None)
        self.pool.submit(self._execute, run_id)
        return self.store.get(run_id)

    def provide_answers(self, run_id: int, answers: dict) -> dict:
        run = self.store.get(run_id)
        if run["status"] != "NEEDS_INPUT":
            raise AgentError(f"Run {run_id} is {run['status']}, not waiting for answers", 409)
        known = {q["id"] for q in (run["understanding"] or {}).get("questions", [])}
        unknown = set(answers) - known
        if unknown:
            raise AgentError(f"Unknown question ids: {sorted(unknown)}", 400)
        self.store.update(run_id, answers={k: str(v).strip() for k, v in answers.items()})
        return self.resume(run_id)

    def cancel(self, run_id: int) -> dict:
        run = self.store.get(run_id)
        if run["status"] in WAITING or run["status"] == "QUEUED":
            self.store.update(run_id, cancel_requested=True, status="CANCELLED", stage="Cancelled",
                              finished_at=now())
        elif run["status"] == "RUNNING":
            self.store.update(run_id, cancel_requested=True)  # takes effect between stages
        return self.store.get(run_id)

    # ---- the worker -------------------------------------------------------------------------
    def _check(self, run_id: int) -> None:
        if self.store.get(run_id)["cancel_requested"]:
            raise Cancelled()

    def _execute(self, run_id: int) -> None:
        run = self.store.get(run_id)
        if run["status"] != "QUEUED":  # cancelled while waiting in the queue
            return
        self.store.update(run_id, status="RUNNING", started_at=run["started_at"] or now())
        ctx = StageContext(self.services, run_id)
        finished = {s["stage"] for s in run["steps"] if s["status"] == "DONE"}
        try:
            for stage in load_stages():
                if stage.NAME in finished:
                    continue
                self._check(run_id)
                self.store.begin_step(run_id, stage.NAME)
                try:
                    summary = stage.run(ctx)
                except StageStop as stop:
                    self.store.end_step(run_id, "DONE" if stop.step_done else "WAITING", stop.summary or stop.label)
                    self.store.update(run_id, status=stop.status, stage=stop.label)
                    return
                except Exception as e:
                    self.store.end_step(run_id, "FAILED", str(e)[:300])
                    raise
                self.store.end_step(run_id, "DONE", summary)
            self.store.update(run_id, status="CONTEXT_READY", stage="Context ready", finished_at=now())
        except Cancelled:
            self.store.update(run_id, status="CANCELLED", stage="Cancelled", finished_at=now())
        except AgentError as e:
            self.store.update(run_id, status="FAILED", stage="Failed", error=e.message, finished_at=now())
        except Exception as e:  # never let the worker thread die silently
            log.exception("Run %s crashed", run_id)
            self.store.update(run_id, status="FAILED", stage="Failed", error=f"{type(e).__name__}: {e}"[:500],
                              finished_at=now())