"""Pin the starting state: which commit is the project at, and is the working copy dirty?"""
from __future__ import annotations

import subprocess
from pathlib import Path


def _git(root: Path, *args: str):
    try:
        out = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.SubprocessError):
        return None
    return out.stdout.strip() if out.returncode == 0 else None


def repo_state(root: Path) -> dict:
    head = _git(root, "rev-parse", "HEAD")
    if head is None:
        return {"is_git_repo": False, "base_commit": None, "branch": None, "uncommitted_files": 0}
    porcelain = _git(root, "status", "--porcelain") or ""
    return {
        "is_git_repo": True,
        "base_commit": head,
        "branch": _git(root, "rev-parse", "--abbrev-ref", "HEAD"),
        "uncommitted_files": len([line for line in porcelain.splitlines() if line.strip()]),
    }