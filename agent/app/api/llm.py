from fastapi import APIRouter

from app.deps import get_services
from app.llm.base import ChatMessage, UsageTracker
from app.llm.prompts import render
from app.llm.structured import generate_json

router = APIRouter(prefix="/llm", tags=["llm"])


@router.get("/status")
def status() -> dict:
    return get_services().llm.status()


@router.post("/ping")
def ping() -> dict:
    """Asks the model for a tiny JSON reply. Proves the model is installed AND can follow a JSON instruction."""
    s = get_services()
    prompt = render("ping")
    usage = UsageTracker(limit_tokens=2000)

    def validate(data):
        return [] if isinstance(data, dict) and data.get("ok") is True else ['expected {"ok": true, "word": "..."}']

    data = generate_json(s.llm, [ChatMessage("user", prompt.text)], validate, usage=usage, purpose="ping",
                         max_output_tokens=60)
    return {"ok": True, "reply": data, "prompt": f"{prompt.name}.v{prompt.version}", "usage": usage.summary()}