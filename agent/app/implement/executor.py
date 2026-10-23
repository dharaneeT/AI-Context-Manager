"""Executes the approved plan one step at a time: focused context, SEARCH/REPLACE edits, a checkpoint after every
step, one retry that shows the model what was wrong. Stops at the first step that cannot be done."""
from __future__ import annotations

import logging

from app.context.files import list_project_files
from app.core.errors import AgentError
from app.editing.engine import EditError, apply_step, delete_files
from app.editing.parser import parse_edits
from app.implement.context import build_step_prompt
from app.implement.migrations import migration_note
from app.llm.base import ChatMessage, UsageTracker
from app.orchestrator.store import now
from app.sandbox import workspace as ws

log = logging.getLogger("agent.implement")


def _attempt(settings, llm, usage, tree, prompt_text: str, step: dict, label: str, history: list):
    """One model call plus applying its edits. Returns (report, problem). Exactly one of them is None."""
    messages = [ChatMessage("user", prompt_text), *history]
    result = llm.chat(messages, json_mode=False, temperature=0.1)
    usage.add(result, label)
    edits, problems = parse_edits(result.text)
    if problems:
        return None, "; ".join(problems), result.text
    try:
        return apply_step(tree, edits, set(step["files"]), settings.max_file_bytes, label), None, result.text
    except EditError as e:
        return None, "; ".join(e.problems), result.text


def implement_plan(ctx, tree, plan: dict) -> dict:
    """Runs every non-verify step. Saves progress after each one so a restart or Cancel loses nothing."""
    s = ctx.services
    settings = ctx.settings
    run = ctx.run
    record = run.get("implementation") or {"steps": [], "usage": None, "notes": [], "outcome": "RUNNING"}
    done = {x["id"] for x in record["steps"] if x["status"] == "DONE"}
    record["steps"] = [x for x in record["steps"] if x["status"] == "DONE"]
    todo = [st for st in plan["steps"] if st["action"] != "verify" and st["id"] not in done]
    usage = UsageTracker(limit_tokens=settings.llm_num_ctx * 4 * max(1, len(todo)))
    earlier = [f for x in record["steps"] for f in x.get("files", [])]
    all_files = list_project_files(tree)

    for step in todo:
        ctx.check_cancel()
        entry = {"id": step["id"], "title": step["title"], "action": step["action"], "status": "RUNNING",
                 "attempts": 0, "files": [], "problem": None, "checkpoint": None}
        record["steps"].append(entry)
        ctx.save(implementation=record)
        label = f"{step['id']}: {step['title']}"[:100]
        if step["action"] == "delete":
            try:
                reports = delete_files(tree, step["files"], set(step["files"]))
                entry.update(status="DONE", attempts=1, files=[r["path"] for r in reports],
                             checkpoint=ws.checkpoint(tree, label))
            except EditError as e:
                entry.update(status="FAILED", problem="; ".join(e.problems))
        else:
            history, problem = [], None
            for attempt in range(1, settings.implement_attempts + 1):
                entry["attempts"] = attempt
                prompt = build_step_prompt(settings, ctx.run, step, tree, earlier, all_files)
                try:
                    report, problem, raw = _attempt(settings, s.llm, usage, tree, prompt.text, step, label, history)
                except AgentError as e:  # model unreachable, budget used up ...
                    report, problem, raw = None, e.message, ""
                    entry.update(status="FAILED", problem=problem)
                    break
                if report:
                    entry.update(status="DONE", files=[f["path"] for f in report["files"]],
                                 checkpoint=report["checkpoint"], problem=None)
                    s.store.append(run["id"], "changes", {**report, "step": step["id"], "at": now()})
                    break
                history = [ChatMessage("assistant", raw),
                           ChatMessage("user", f"Your edits were refused: {problem}\n"
                                               "Reply again with ONLY corrected edit blocks.")]
                entry.update(status="FAILED", problem=problem)
        earlier += [f for f in entry["files"] if f not in earlier]
        record["usage"] = usage.summary()
        ctx.save(implementation=record)
        if entry["status"] != "DONE":
            record["outcome"] = "FAILED_STEP"
            ctx.save(implementation=record)
            return record
    record["outcome"] = "DONE"
    changed = [c["path"] for c in ws.changed_files(tree, ctx.run["workspace"]["baseline"])]
    note = migration_note(ctx.run.get("profile") or {}, changed, all_files)
    if note:
        record["notes"] = [*record.get("notes", []), note["message"]]
        record["migration"] = note
    ctx.save(implementation=record)
    return record