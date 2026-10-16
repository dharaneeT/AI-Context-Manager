"""Minimal JSON-over-HTTP helper built on the standard library (no extra dependency, very light on RAM)."""
from __future__ import annotations

import json
import socket
import urllib.error
import urllib.parse
import urllib.request


class HttpFailure(Exception):
    """The server answered with an error status."""

    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body[:300]}")
        self.status = status
        self.body = body


class ConnectionFailure(Exception):
    """The server could not be reached (not running, wrong port, timeout)."""


def request_json(method: str, url: str, *, params: dict | None = None, body: dict | None = None,
                 headers: dict | None = None, timeout: float = 30.0):
    if params:
        clean = {k: v for k, v in params.items() if v is not None}
        url = f"{url}?{urllib.parse.urlencode(clean)}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method.upper())
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        raise HttpFailure(e.code, e.read().decode("utf-8", errors="replace")) from e
    except (urllib.error.URLError, socket.timeout, ConnectionError, TimeoutError) as e:
        raise ConnectionFailure(f"Cannot reach {url.split('?')[0]}: {e}") from e
    if not raw.strip():
        return None
    return json.loads(raw)