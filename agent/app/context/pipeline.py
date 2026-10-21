"""The Day 1-5 pipeline in three callable stages: understand -> discover -> pack."""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from app.context.candidates import Candidate, build_candidates
from app.context.files import list_project_files, map_root
from app.context.git import repo_state
from app.context.pack import Pack, build_pack
from app.context.understand import Understanding, understand
from app.core.config import Settings


@dataclass
class Discovery:
    root: Path
    all_files: list
    candidates: list
    repo: dict
    notes: list = field(default_factory=list)


def discover(settings: Settings, backend, project_id: int, command: str, understanding: Understanding) -> Discovery:
    project = backend.project(project_id)
    root = map_root(project["rootPath"], settings.path_map)
    all_files = list_project_files(root)
    notes: list = []
    candidates = build_candidates(backend, project_id, command, understanding, all_files, notes)
    return Discovery(root, all_files, candidates, repo_state(root), notes)


def make_pack(settings: Settings, found: Discovery, command: str, max_tokens: int | None) -> Pack:
    budget = max_tokens or settings.pack_default_tokens
    return build_pack(found.root, found.candidates, command, budget, settings.max_file_bytes, found.repo, found.notes)


def run_all(settings: Settings, backend, project_id: int, command: str, max_tokens: int | None) -> tuple:
    u = understand(command)
    found = discover(settings, backend, project_id, command, u)
    return u, found, make_pack(settings, found, command, max_tokens)