from fastapi import APIRouter

from app.deps import get_services

router = APIRouter(tags=["info"])  # secured: needs X-Agent-Secret


@router.get("/info")
def info() -> dict:
    """Non-secret configuration summary. Also the easiest way to test that the shared secret works."""
    s = get_services().settings
    return {"llmProvider": s.llm_provider, "model": s.ollama_model if s.llm_provider == "ollama" else s.openai_model,
            "numCtx": s.llm_num_ctx, "backendUrl": s.backend_url, "pathMapEntries": len(s.path_map),
            "dataDir": str(s.data_dir), "packDefaultTokens": s.pack_default_tokens}