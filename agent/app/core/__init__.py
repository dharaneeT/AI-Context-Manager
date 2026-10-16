"""Service-to-service authentication: Spring sends X-Agent-Secret, we compare in constant time."""
from __future__ import annotations

import hmac

from fastapi import Header, HTTPException

from app.deps import get_services


def require_secret(x_agent_secret: str = Header(default="")) -> None:
    expected = get_services().settings.shared_secret
    if not expected:
        raise HTTPException(status_code=503, detail="AGENT_SHARED_SECRET is not set in agent/.env")
    if not hmac.compare_digest(x_agent_secret.encode(), expected.encode()):
        raise HTTPException(status_code=401, detail="Bad or missing X-Agent-Secret")