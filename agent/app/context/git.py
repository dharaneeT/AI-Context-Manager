"""Client for the Spring Boot backend: the existing search, ranking and graph endpoints become the agent's evidence."""
from __future__ import annotations

from app.core.config import Settings
from app.core.errors import BackendUnavailable, NotFound
from app.core.http import ConnectionFailure, HttpFailure, request_json


class BackendClient:
    def __init__(self, settings: Settings):
        self.base = settings.backend_url

    def _get(self, path: str, params: dict | None = None):
        try:
            return request_json("GET", f"{self.base}{path}", params=params, timeout=60)
        except ConnectionFailure as e:
            raise BackendUnavailable(f"Spring backend not reachable at {self.base}. Is it running? ({e})") from e
        except HttpFailure as e:
            if e.status == 404:
                raise NotFound(f"Backend says not found: {path}") from e
            raise BackendUnavailable(f"Backend error on {path}: {e}") from e

    def health(self):
        return self._get("/api/health")

    def project(self, project_id: int) -> dict:
        return self._get(f"/api/projects/{project_id}")

    def hybrid_search(self, project_id: int, query: str, limit: int = 15) -> dict:
        return self._get(f"/api/projects/{project_id}/hybrid-search", {"q": query, "limit": limit})

    def rank(self, project_id: int, task: str, limit: int = 25) -> dict:
        return self._get(f"/api/projects/{project_id}/rank", {"task": task, "limit": limit})

    def dependencies(self, project_id: int, path: str) -> list:
        return self._get(f"/api/projects/{project_id}/graph/dependencies", {"path": path}) or []

    def dependents(self, project_id: int, path: str) -> list:
        return self._get(f"/api/projects/{project_id}/graph/dependents", {"path": path}) or []

    def symbols(self, project_id: int, path: str) -> list:
        return self._get(f"/api/projects/{project_id}/symbols", {"path": path}) or []