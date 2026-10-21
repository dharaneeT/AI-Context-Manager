"""Plan generation: ask the model first, keep the template plan as the safety net."""
from __future__ import annotations

import logging

from app.context.profile import available_commands, conventions_text
from app.core.errors import AgentError
from app.llm.base import ChatMessage, UsageTracker, estimate_tokens
from app.llm.prompts import render
from app.llm.structured import generate_json
from app.planner.plan import _verify_steps, finalize, normalize_plan, template_plan

log = logging.getLogger("agent.plan")


def _component_rows(impact: dict, limit: int = 20) -> str:
    order = {"modify": 0, "test": 1, "reference": 2}
    rows = sorted(impact.get("components", []), key=lambda c: (order[c["role"]], -c["score"]))[:limit]
    return "\n".join(f"{c['layer']} | {c['role']} | {c['path']}" for c in rows) or "(none)"


def build_prompt(understanding: dict, impact: dict, profile: dict, commands: list, max_chars_conventions: int = 700):
    new = impact.get("suggested_new_files", [])
    return render(
        "plan_steps",
        summary=understanding.get("summary") or understanding.get("command", ""),
        criteria="\n".join(f"- {c}" for c in (understanding.get("acceptance_criteria") or [])[:6]) or "(none)",
        conventions=conventions_text(profile, max_chars_conventions) or "(unknown)",
        components=_component_rows(impact),
        new_files="\n".join(f"{n['layer']} | {n['path']}" for n in new[:8]) or "(none)",
        commands=", ".join(commands) or "(none)")


def generate_plan(settings, llm, run: dict, all_files: list, version: int = 1) -> dict:
    understanding = run.get("understanding") or {}
    impact = run.get("impact") or {}
    profile = run.get("profile") or {}
    commands = available_commands(profile)
    files, command_set = set(all_files), set(commands)
    reason = ""

    if settings.use_llm:
        prompt = build_prompt(understanding, impact, profile, commands)
        if estimate_tokens(prompt.text) <= settings.prompt_budget_tokens:
            def validate(data):
                if not isinstance(data, dict) or not isinstance(data.get("steps"), list):
                    return ['reply must be a JSON object with a "steps" list']
                return finalize(normalize_plan(data, version, "llm"), files, command_set)["problems"]

            usage = UsageTracker(limit_tokens=settings.llm_num_ctx * 3)
            try:
                data = generate_json(llm, [ChatMessage("user", prompt.text)], validate, usage=usage, purpose="plan",
                                     max_attempts=2)
                plan = normalize_plan(data, version, "llm")
                touched = [f for s in plan["steps"] for f in s["files"]]
                if not any(s["action"] == "verify" for s in plan["steps"]):  # the model forgot: add them ourselves
                    for key in _verify_steps(touched, profile):
                        n = len(plan["steps"]) + 1
                        plan["steps"].append({"id": f"s{n}", "title": f"Verify: {key}", "action": "verify",
                                              "files": [], "verify": key, "depends_on": [f"s{n - 1}"],
                                              "description": f"Run {key} and fix anything it reports."})
                plan["model_usage"] = usage.summary()
                return finalize(plan, files, command_set)
            except AgentError as e:
                reason = e.message
                log.warning("Plan fell back to the template: %s", reason)
        else:
            reason = "the planning prompt does not fit the model's context window"
    else:
        reason = "AGENT_USE_LLM is false"

    raw = template_plan(understanding, impact, profile)
    raw["fallback_reason"] = reason
    return finalize(normalize_plan(raw, version, "template"), files, command_set)