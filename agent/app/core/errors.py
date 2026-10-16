"""One error type and one JSON shape, matching the Spring backend's ApiError."""
from __future__ import annotations

from datetime import datetime, timezone

_REASONS = {400: "Bad Request", 401: "Unauthorized", 404: "Not Found", 409: "Conflict",
            502: "Bad Gateway", 503: "Service Unavailable"}


class AgentError(Exception):
    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.message = message
        self.status = status


class NotFound(AgentError):
    def __init__(self, message: str):
        super().__init__(message, 404)


class BackendUnavailable(AgentError):
    def __init__(self, message: str):
        super().__init__(message, 502)


class LlmUnavailable(AgentError):
    def __init__(self, message: str):
        super().__init__(message, 503)


def error_body(status: int, message: str, path: str) -> dict:
    return {
        "status": status,
        "error": _REASONS.get(status, "Error"),
        "message": message,
        "path": path,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "fieldErrors": None,
    }