
"""Thread-safe run records persisted as JSON files."""
from __future__ import annotations

import json
import threading
from datetime import datetime, timezone
from pathlib import Path

from app.core.errors import AgentError, NotFound

ACTIVE = {"QUEUED", "RUNNING"}


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


class RunStore:
    def __init__(self, data_dir: Path):
        self.dir = Path(data_dir) / "runs"
        self.dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._runs: dict = {}

        for file in self.dir.glob("*.json"):
            try:
                run = json.loads(file.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue

            if run.get("status") in ACTIVE:
                run.update(
                    status="FAILED",
                    stage="Interrupted",
                    error="Interrupted by an agent service restart",
                    finished_at=now(),
                )
                self._write(run)

            self._runs[run["id"]] = run

    def _write(self, run: dict) -> None:
        path = self.dir / f"{run['id']}.json"
        path.write_text(
            json.dumps(run, indent=2),
            encoding="utf-8",
        )

    def create(
        self,
        run_id: int,
        project_id: int,
        command: str,
        max_tokens,
    ) -> dict:
        with self._lock:
            existing = self._runs.get(run_id)

            if existing and existing["status"] in ACTIVE:
                raise AgentError(
                    f"Run {run_id} is already {existing['status']}",
                    409,
                )

            run = {
                "id": run_id,
                "project_id": project_id,
                "command": command,
                "max_tokens": max_tokens,
                "status": "QUEUED",
                "stage": "Queued",
                "error": None,
                "created_at": now(),
                "started_at": None,
                "finished_at": None,
                "steps": [],
                "understanding": None,
                "pack": None,
                "cancel_requested": False,
            }

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
            runs = sorted(
                self._runs.values(),
                key=lambda run: run["created_at"],
                reverse=True,
            )[:limit]

            return [
                {
                    key: value
                    for key, value in run.items()
                    if key not in ("pack", "steps")
                }
                for run in runs
            ]

    def update(self, run_id: int, **fields) -> None:
        with self._lock:
            run = self._runs[run_id]
            run.update(fields)
            self._write(run)

    def begin_step(self, run_id: int, stage: str) -> None:
        with self._lock:
            run = self._runs[run_id]
            run["stage"] = stage
            run["steps"].append({
                "stage": stage,
                "status": "RUNNING",
                "started_at": now(),
                "finished_at": None,
                "summary": "",
            })
            self._write(run)

    def end_step(
        self,
        run_id: int,
        status: str,
        summary: str,
    ) -> None:
        with self._lock:
            run = self._runs[run_id]
            step = run["steps"][-1]
            step.update(
                status=status,
                finished_at=now(),
                summary=summary,
            )
            self._write(run)

    def save_pack_markdown(
        self,
        run_id: int,
        markdown: str,
    ) -> None:
        path = self.dir / f"{run_id}.pack.md"
        path.write_text(markdown, encoding="utf-8")

    def pack_markdown(self, run_id: int) -> str:
        path = self.dir / f"{run_id}.pack.md"

        if not path.is_file():
            raise NotFound(
                f"Run {run_id} has no context pack yet"
            )

        return path.read_text(encoding="utf-8")
