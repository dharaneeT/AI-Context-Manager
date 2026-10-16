"""FastAPI entry point. Run from the agent folder:  uvicorn app.main:app --port 8001

Every module in app/api/ that defines `router` is served automatically. A module with PUBLIC = True is open
(no secret); all others live under /v1 and require the X-Agent-Secret header. New day, new file, no edits here.
"""
from __future__ import annotations

import importlib
import pkgutil

from fastapi import APIRouter, Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

import app.api as api_package
from app import __version__
from app.core.config import load_settings
from app.core.errors import AgentError, error_body
from app.core.logging import setup_logging
from app.core.security import require_secret


def _include_routers(app: FastAPI) -> None:
    secured = APIRouter(prefix="/v1", dependencies=[Depends(require_secret)])
    for info in sorted(pkgutil.iter_modules(api_package.__path__), key=lambda m: m.name):
        module = importlib.import_module(f"app.api.{info.name}")
        router = getattr(module, "router", None)
        if router is None:
            continue
        if getattr(module, "PUBLIC", False):
            app.include_router(router)
        else:
            secured.include_router(router)
    app.include_router(secured)


def create_app() -> FastAPI:
    setup_logging()
    app = FastAPI(title="ContextLayer Agent", version=__version__)

    @app.exception_handler(AgentError)
    async def agent_error(request: Request, exc: AgentError):
        return JSONResponse(error_body(exc.status, exc.message, request.url.path), status_code=exc.status)

    @app.exception_handler(StarletteHTTPException)
    async def http_error(request: Request, exc: StarletteHTTPException):
        return JSONResponse(error_body(exc.status_code, str(exc.detail), request.url.path), status_code=exc.status_code)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exc: RequestValidationError):
        first = exc.errors()[0] if exc.errors() else {}
        where = ".".join(str(p) for p in first.get("loc", [])[1:])
        return JSONResponse(error_body(400, f"Validation failed: {where} {first.get('msg', '')}".strip(),
                                       request.url.path), status_code=400)

    _include_routers(app)
    return app


app = create_app()


if __name__ == "__main__":  # python -m app.main  (uses AGENT_HOST / AGENT_PORT from .env)
    import uvicorn

    _s = load_settings()
    uvicorn.run("app.main:app", host=_s.host, port=_s.port)