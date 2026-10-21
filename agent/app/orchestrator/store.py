"""Run records kept in memory and saved as JSON files, so a restart does not lose history."""
from __future__ import annotations

import json
import threading
from datetime import datetime, timezone
from pathlib import Path

from app.core.errors import AgentError, NotFound

ACTIVE = {"QUEUED", "RUNNING"}                      # a worker is (or will be) busy with it
WAITING = {"NEEDS_INPUT", "AWAITING_APPROVAL"}      # parked until a person acts; survives a restart
HEAVY_KEYS = {"pack", "steps", "candidates", "profile", "impact", "plan", "plans", "approvals", "understanding"}


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


class RunStore:
    def __init__(self, data_dir: Path):
        self.dir = Path(data_dir) / "runs"
        self.dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._runs: dict = {}
        for f in self.dir.glob("*.json"):
            try:
                run = json.loads(f.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            if run.get("status") in ACTIVE:  # the process died while this run was active
                run.update(status="FAILED", stage="Interrupted", error="Interrupted by an agent service restart",
                           finished_at=now())
                self._write(run)
            self._runs[run["id"]] = run

    def _write(self, run: dict) -> None:
        (self.dir / f"{run['id']}.json").write_text(json.dumps(run, indent=2), encoding="utf-8")

    def create(self, run_id: int, project_id: int, command: str, max_tokens) -> dict:
        with self._lock:
            existing = self._runs.get(run_id)
            if existing and existing["status"] in ACTIVE | WAITING:
                raise AgentError(f"Run {run_id} is already {existing['status']}", 409)
            run = {"id": run_id, "project_id": project_id, "command": command, "max_tokens": max_tokens,
                   "status": "QUEUED", "stage": "Queued", "error": None, "created_at": now(), "started_at": None,
                   "finished_at": None, "steps": [], "understanding": None, "answers": None, "profile": None,
                   "candidates": None, "impact": None, "pack": None, "plan": None, "plans": [], "approvals": [],
                   "cancel_requested": False}
            self._runs[run_id] = run
            self._write(run)
            return json.loads(json.dumps(run))

    def get(self, run_id: int) -> dict:
        with self._lock:
            if run_id not in self._runs:
                raise NotFound(f"Run {run_id} not found")
            return json.loads(json.dumps(self._runs[run_id]))

    def list(self, limit: int = 20) -> list:
        with self._lock:
            runs = sorted(self._runs.values(), key=lambda r: r["created_at"], reverse=True)[:limit]
            return [{k: v for k, v in r.items() if k not in HEAVY_KEYS} for r in runs]

    def update(self, run_id: int, **fields) -> None:
        with self._lock:
            run = self._runs[run_id]
            run.update(fields)
            self._write(run)

    def append(self, run_id: int, key: str, value) -> None:
        """Add one item to a list field (for example an approval record)."""
        with self._lock:
            run = self._runs[run_id]
            run.setdefault(key, []).append(value)
            self._write(run)

    def begin_step(self, run_id: int, stage: str) -> None:
        with self._lock:
            run = self._runs[run_id]
            run["stage"] = stage
            run["steps"].append({"stage": stage, "status": "RUNNING", "started_at": now(), "finished_at": None,
                                 "summary": ""})
            self._write(run)

    def end_step(self, run_id: int, status: str, summary: str) -> None:
        with self._lock:
            run = self._runs[run_id]
            step = run["steps"][-1]
            step.update(status=status, finished_at=now(), summary=summary)
            self._write(run)

    def save_text(self, run_id: int, name: str, text: str) -> None:
        (self.dir / f"{run_id}.{name}").write_text(text, encoding="utf-8")

    def read_text(self, run_id: int, name: str) -> str:
        path = self.dir / f"{run_id}.{name}"
        if not path.is_file():
            raise NotFound(f"Run {run_id} has no {name} yet")
        return path.read_text(encoding="utf-8")

    # kept from Day 2 so existing callers still work
    def save_pack_markdown(self, run_id: int, markdown: str) -> None:
        self.save_text(run_id, "pack.md", markdown)

    def pack_markdown(self, run_id: int) -> str:
        return self.read_text(run_id, "pack.md")