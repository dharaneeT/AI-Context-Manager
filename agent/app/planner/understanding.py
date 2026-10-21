"""Task understanding with the LLM, backed by the heuristics so the pipeline never depends on the model."""
from __future__ import annotations

import logging

from app.context.understand import VALID_AREAS, Understanding, understand
from app.core.errors import AgentError
from app.llm.base import ChatMessage, UsageTracker, estimate_tokens
from app.llm.prompts import render
from app.llm.structured import generate_json

log = logging.getLogger("agent.understanding")


def _str_list(value, limit: int) -> list:
    return [str(v).strip() for v in value[:limit] if str(v).strip()] if isinstance(value, list) else []


def make_validator(max_questions: int):
    def validate(data) -> list:
        if not isinstance(data, dict):
            return ["the reply must be a JSON object"]
        problems = []
        if not str(data.get("summary", "")).strip():
            problems.append('"summary" is missing')
        criteria = data.get("acceptance_criteria")
        if not isinstance(criteria, list) or not 1 <= len([c for c in criteria if str(c).strip()]) <= 8:
            problems.append('"acceptance_criteria" must be a list of 1 to 8 statements')
        if not isinstance(data.get("questions", []), list):
            problems.append('"questions" must be a list')
        elif len(data.get("questions", [])) > max_questions:
            problems.append(f'"questions" may have at most {max_questions} items')
        return problems
    return validate


def format_answers(questions: list, answers: dict | None) -> str:
    if not answers:
        return "(none)"
    by_id = {q["id"]: q["question"] for q in questions}
    return "\n".join(f"- {by_id.get(k, k)} -> {v}" for k, v in answers.items() if v) or "(none)"


def fallback(base: Understanding, reason: str) -> Understanding:
    """No model (or it failed): the comenter code heremand itself becomes the single criterion, and we say so."""
    base.summary = base.command[:200]
    base.acceptance_criteria = [base.command[:300]]
    base.assumptions = ["No language model was used to analyse this task: " + reason]
    base.source, base.llm_used, base.note = "heuristic", False, reason
    return base


def analyze(services, command: str, profile: dict | None, answers: dict | None,
            previous_questions: list | None = None) -> Understanding:
    settings = services.settings
    base = understand(command)
    if not settings.use_llm:
        return fallback(base, "AGENT_USE_LLM is false")
    conventions = ""
    if profile:  # the profile arrives on Day 7; before that this stays empty
        from app.context.profile import conventions_text
        conventions = conventions_text(profile, 700)
    prompt = render("understand_task", command=command, conventions=conventions or "(unknown)",
                    answers=format_answers(previous_questions or [], answers), max_questions=settings.max_questions)
    if estimate_tokens(prompt.text) > settings.prompt_budget_tokens:
        return fallback(base, "the prompt does not fit the model's context window")
    usage = UsageTracker(limit_tokens=settings.llm_num_ctx * 3)
    try:
        data = generate_json(services.llm, [ChatMessage("user", prompt.text)], make_validator(settings.max_questions),
                             usage=usage, purpose="understand", max_attempts=2)
    except AgentError as e:
        log.warning("Understanding fell back to heuristics: %s", e.message)
        return fallback(base, e.message)
    base.summary = str(data["summary"]).strip()[:300]
    base.acceptance_criteria = _str_list(data["acceptance_criteria"], 8)
    base.constraints = _str_list(data.get("constraints"), 6)
    base.assumptions = _str_list(data.get("assumptions"), 6)
    areas = [a for a in _str_list(data.get("areas"), 5) if a in VALID_AREAS]
    base.areas = sorted(set(base.areas) | set(areas))
    questions = []
    for i, q in enumerate(data.get("questions") or [], 1):
        if isinstance(q, dict) and str(q.get("question", "")).strip():
            questions.append({"id": f"q{i}", "question": str(q["question"]).strip()[:300],
                              "why": str(q.get("why", "")).strip()[:300]})
    base.questions = questions[: settings.max_questions]
    base.needs_clarification = bool(data.get("needs_clarification")) and bool(base.questions) and answers is None
    if answers is not None:  # the user already answered: keep their questions for display, never ask again
        base.questions, base.answers, base.needs_clarification = list(previous_questions or []), answers, False
    base.source, base.llm_used = "llm", True
    base.note = f"{usage.calls} model call(s), {usage.total} tokens"
    return base