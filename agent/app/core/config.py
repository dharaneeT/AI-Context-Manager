"""Settings read from environment variables (and an optional .env file). Standard library only."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

AGENT_DIR = Path(__file__).resolve().parents[2]


def _load_dotenv(path: Path) -> None:
    """Tiny .env reader: KEY=VALUE per line. Real environment variables win over the file."""
    if not path.is_file():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def _int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    host: str = "127.0.0.1"
    port: int = 8001
    shared_secret: str = ""
    backend_url: str = "http://localhost:8080"
    path_map: tuple = ()
    llm_provider: str = "ollama"
    ollama_url: str = "http://localhost:11434"
    ollama_model: str = "qwen2.5-coder:1.5b"
    llm_num_ctx: int = 4096
    llm_max_output_tokens: int = 700
    llm_timeout_seconds: int = 300
    llm_keep_alive: str = "30s"
    openai_base_url: str = ""
    openai_api_key: str = ""
    openai_model: str = ""
    data_dir: Path = field(default_factory=lambda: AGENT_DIR / ".agent_data")
    pack_default_tokens: int = 6000
    max_file_bytes: int = 300_000


def _parse_path_map(raw: str) -> tuple:
    pairs = []
    for part in raw.split(";"):
        if "=" in part:
            container, _, host = part.partition("=")
            if container.strip() and host.strip():
                pairs.append((container.strip().rstrip("/"), host.strip().rstrip("/\\")))
    return tuple(pairs)


def load_settings() -> Settings:
    _load_dotenv(AGENT_DIR / ".env")
    data_dir = Path(os.environ.get("AGENT_DATA_DIR", ".agent_data"))
    if not data_dir.is_absolute():
        data_dir = AGENT_DIR / data_dir
    return Settings(
        host=os.environ.get("AGENT_HOST", "127.0.0.1"),
        port=_int("AGENT_PORT", 8001),
        shared_secret=os.environ.get("AGENT_SHARED_SECRET", ""),
        backend_url=os.environ.get("BACKEND_URL", "http://localhost:8080").rstrip("/"),
        path_map=_parse_path_map(os.environ.get("AGENT_PATH_MAP", "")),
        llm_provider=os.environ.get("LLM_PROVIDER", "ollama").lower(),
        ollama_url=os.environ.get("OLLAMA_URL", "http://localhost:11434").rstrip("/"),
        ollama_model=os.environ.get("OLLAMA_MODEL", "qwen2.5-coder:1.5b"),
        llm_num_ctx=_int("LLM_NUM_CTX", 4096),
        llm_max_output_tokens=_int("LLM_MAX_OUTPUT_TOKENS", 700),
        llm_timeout_seconds=_int("LLM_TIMEOUT_SECONDS", 300),
        llm_keep_alive=os.environ.get("LLM_KEEP_ALIVE", "30s"),
        openai_base_url=os.environ.get("OPENAI_BASE_URL", "").rstrip("/"),
        openai_api_key=os.environ.get("OPENAI_API_KEY", ""),
        openai_model=os.environ.get("OPENAI_MODEL", ""),
        data_dir=data_dir,
        pack_default_tokens=_int("PACK_DEFAULT_TOKENS", 6000),
        max_file_bytes=_int("MAX_FILE_BYTES", 300_000),
    )