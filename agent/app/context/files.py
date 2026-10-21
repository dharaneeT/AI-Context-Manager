"""Safe access to a project's files: path resolution, ignore rules, listing, and full-file reading with redaction."""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from app.context.redact import redact
from app.core.errors import AgentError, NotFound

# Mirrors contextlayer.indexing.* in the backend's application.properties.
IGNORED_DIRS = {".git", "node_modules", "target", "build", "dist", "out", ".idea", ".vscode", ".gradle", ".mvn",
                "__pycache__", ".venv", "venv", "coverage", ".next", "pgdata", ".agent_data"}
IGNORED_EXTS = {"png", "jpg", "jpeg", "gif", "webp", "ico", "svg", "pdf", "zip", "jar", "class", "exe", "dll", "so",
                "woff", "woff2", "ttf", "mp3", "mp4", "lock", "log", "pem", "key", "p12", "pfx", "jks", "keystore"}
IGNORED_NAMES = {"package-lock.json", "pnpm-lock.yaml", "yarn.lock", "mvnw", "mvnw.cmd", ".ds_store", "thumbs.db",
                 ".env", ".env.local", ".env.production", "id_rsa", "id_ed25519", ".npmrc", ".pypirc",
                 "credentials", "secrets.json"}

LANGUAGES = {"java": "java", "py": "python", "js": "javascript", "jsx": "jsx", "ts": "typescript", "tsx": "tsx",
             "sql": "sql", "xml": "xml", "json": "json", "yml": "yaml", "yaml": "yaml", "md": "markdown",
             "properties": "properties", "html": "html", "css": "css", "sh": "bash", "http": "http"}


def map_root(root_path: str, path_map: tuple) -> Path:
    """Turn the backend's project path into a path on THIS machine (needed when the backend runs in Docker)."""
    normalized = root_path.replace("\\", "/")
    for container_prefix, host_prefix in path_map:
        if normalized == container_prefix or normalized.startswith(container_prefix + "/"):
            normalized = host_prefix.replace("\\", "/") + normalized[len(container_prefix):]
            break
    path = Path(normalized)
    if not path.is_dir():
        raise NotFound(f"Project folder not found on this machine: {path}. "
                       f"If the backend runs in Docker, set AGENT_PATH_MAP in agent/.env.")
    return path.resolve()


def safe_join(root: Path, relative: str) -> Path:
    """Join and refuse anything that escapes the project root (../, absolute paths, symlinks out)."""
    candidate = (root / relative).resolve()
    if candidate != root and root not in candidate.parents:
        raise AgentError(f"Path escapes the project folder: {relative}", 400)
    return candidate


def is_ignored(relative: str) -> bool:
    parts = relative.replace("\\", "/").split("/")
    if any(p in IGNORED_DIRS for p in parts[:-1]):
        return True
    name = parts[-1]
    if name.lower() in IGNORED_NAMES:
        return True
    ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    return ext in IGNORED_EXTS


def language_of(path: str) -> str:
    ext = path.rsplit(".", 1)[-1].lower() if "." in path else ""
    return LANGUAGES.get(ext, "")


def list_project_files(root: Path, limit: int = 20000) -> list:
    """Every non-ignored file as a '/'-separated relative path (used for test and config discovery)."""
    found = []
    for current, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in IGNORED_DIRS]
        for name in names:
            rel = os.path.relpath(os.path.join(current, name), root).replace("\\", "/")
            if not is_ignored(rel):
                found.append(rel)
                if len(found) >= limit:
                    return sorted(found)
    return sorted(found)


@dataclass
class FileContent:
    path: str
    text: str
    line_count: int
    truncated: bool
    redactions: int
    skipped: str = ""  # non-empty = why the file was not read

    @property
    def language(self) -> str:
        return language_of(self.path)


def read_file(root: Path, relative: str, max_bytes: int) -> FileContent:
    """Read ONE COMPLETE file (not a chunk). Skips ignored, missing, binary and oversize files with a reason."""
    if is_ignored(relative):
        return FileContent(relative, "", 0, False, 0, skipped="ignored by rules")
    target = safe_join(root, relative)
    if not target.is_file():
        return FileContent(relative, "", 0, False, 0, skipped="not found")
    size = target.stat().st_size
    with open(target, "rb") as handle:
        raw = handle.read(max_bytes + 1)
    if b"\x00" in raw[:4096]:
        return FileContent(relative, "", 0, False, 0, skipped="binary file")
    truncated = size > max_bytes or len(raw) > max_bytes
    text = raw[:max_bytes].decode("utf-8", errors="replace").replace("\r\n", "\n")
    text, redactions = redact(text)
    return FileContent(relative, text, text.count("\n") + 1 if text else 0, truncated, redactions)