"""The shared objects (settings, backend client, LLM, run store, orchestrator), each created the first time it is used."""
from __future__ import annotations

import threading

from app.core.config import Settings, load_settings


class Services:
    def __init__(self, settings: Settings | None = None, backend=None, llm=None, store=None):
        self.settings = settings or load_settings()
        self._backend, self._llm, self._store, self._orchestrator = backend, llm, store, None
        self._lock = threading.RLock()

    @property
    def backend(self):
        with self._lock:
            if self._backend is None:
                from app.backend.client import BackendClient  # imported here so each day's files can arrive in order
                self._backend = BackendClient(self.settings)
            return self._backend

    @property
    def llm(self):
        with self._lock:
            if self._llm is None:
                from app.llm.factory import create_llm
                self._llm = create_llm(self.settings)
            return self._llm

    @property
    def store(self):
        with self._lock:
            if self._store is None:
                from app.orchestrator.store import RunStore
                self._store = RunStore(self.settings.data_dir)
            return self._store

    @property
    def orchestrator(self):
        with self._lock:
            if self._orchestrator is None:
                from app.orchestrator.runner import Orchestrator
                self._orchestrator = Orchestrator(self)
            return self._orchestrator


_services: Services | None = None
_guard = threading.Lock()


def get_services() -> Services:
    global _services
    with _guard:
        if _services is None:
            _services = Services()
        return _services


def set_services(services: Services | None) -> None:
    """Tests use this to swap in fakes."""
    global _services
    with _guard:
        _services = services