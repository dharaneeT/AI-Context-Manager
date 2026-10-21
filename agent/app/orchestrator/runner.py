"""Runs the stages of one task on a single background worker (an 8 GB PC cannot do two LLM jobs at once)."""
from __future__ import annotations

import logging
from concurrent.futures import ThreadPoolExecutor

from app.context import pipeline
from app.context.understand import understand
from app.core.errors import AgentError
from app.orchestrator.store import ACTIVE, now

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

    def submit(self, run_id: int, project_id: int, command: str, max_tokens) -> dict:
        active = [r for r in self.store.list(200) if r["status"] in ACTIVE]
        if len(active) >= MAX_ACTIVE_RUNS:
            raise AgentError("Too many runs are queued. Try again in a moment.", 409)
        run = self.store.create(run_id, project_id, command, max_tokens)
        self.pool.submit(self._execute, run_id)
        return run

    def cancel(self, run_id: int) -> dict:
        run = self.store.get(run_id)
        if run["status"] not in ACTIVE:
            return run
        self.store.update(run_id, cancel_requested=True)
        if run["status"] == "QUEUED":
            self.store.update(run_id, status="CANCELLED", stage="Cancelled", finished_at=now())
        return self.store.get(run_id)

    def _check(self, run_id: int) -> None:
        if self.store.get(run_id)["cancel_requested"]:
            raise Cancelled()

    def _stage(self, run_id: int, name: str, work):
        """Run one stage, recording it as a step with its outcome. `work` returns (value, summary)."""
        self._check(run_id)
        self.store.begin_step(run_id, name)
        try:
            value, summary = work()
        except Exception as e:
            self.store.end_step(run_id, "FAILED", str(e)[:300])
            raise
        self.store.end_step(run_id, "DONE", summary)
        return value

    def _execute(self, run_id: int) -> None:
        run = self.store.get(run_id)
        if run["status"] != "QUEUED":  # cancelled while waiting
            return
        self.store.update(run_id, status="RUNNING", started_at=now())
        try:
            u = self._stage(run_id, "Understand", lambda: self._understand(run))
            found = self._stage(run_id, "Discover", lambda: self._discover(run, u))
            self._stage(run_id, "Pack", lambda: self._pack(run, found))
            self.store.update(run_id, status="CONTEXT_READY", stage="Context ready", finished_at=now())
        except Cancelled:
            self.store.update(run_id, status="CANCELLED", stage="Cancelled", finished_at=now())
        except AgentError as e:
            self.store.update(run_id, status="FAILED", stage="Failed", error=e.message, finished_at=now())
        except Exception as e:  # never let the worker thread die silently
            log.exception("Run %s crashed", run_id)
            self.store.update(run_id, status="FAILED", stage="Failed", error=f"{type(e).__name__}: {e}"[:500],
                              finished_at=now())

    # ---- stages -------------------------------------------------------------------------------
    def _understand(self, run):
        u = understand(run["command"])
        self.store.update(run["id"], understanding=u.as_dict())
        return u, f"areas={u.areas or 'none detected'}, {len(u.keywords)} keywords"

    def _discover(self, run, u):
        s = self.services
        found = pipeline.discover(s.settings, s.backend, run["project_id"], run["command"], u)
        return found, f"{len(found.candidates)} candidate files out of {len(found.all_files)}"

    def _pack(self, run, found):
        pack = pipeline.make_pack(self.services.settings, found, run["command"], run["max_tokens"])
        self.store.save_pack_markdown(run["id"], pack.markdown)
        self.store.update(run["id"], pack=pack.public())
        s = pack.stats()
        return pack, (f"{s['fullFiles']} full, {s['windowedFiles']} excerpts, {s['pathOnlyFiles']} paths, "
                      f"~{s['packTokens']} tokens")