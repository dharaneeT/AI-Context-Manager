"""The bounded repair loop. It tries to fix what verification found, and stops, with a clear reason, as soon as
continuing would be guessing: attempts used, time used, tokens used, an environment problem, the same errors
coming back, an edit outside the approved plan, or a model that cannot produce a valid edit."""
from __future__ import annotations

import time

from app.core.errors import AgentError
from app.editing.engine import EditError, apply_step, normalize_path
from app.editing.parser import parse_edits
from app.editing.scope import approved_files
from app.llm.base import ChatMessage, UsageTracker
from app.orchestrator.store import now
from app.repair.context import build_repair_prompt, pick_targets, weakens_test
from app.verify.runner import describe, failures_of, verify_once

EDIT_TRIES = 2


class Stop(Exception):
    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


def _failed_commands(attempt: dict) -> list:
    return [r["command"] for r in attempt["results"] if not r["passed"]]


def _one_repair(ctx, tree, run, targets, allowed, n, usage, record) -> tuple:
    """Ask for edits (with one retry that shows the refusal), check them, apply them. Returns (edit paths, checkpoint)."""
    s = ctx.services
    prompt = build_repair_prompt(ctx.settings, run, tree, targets, allowed, n, record["attempts"])
    convo, problem = [ChatMessage("user", prompt.text)], ""
    for attempt in range(1, EDIT_TRIES + 1):
        result = s.llm.chat(convo, json_mode=False, temperature=0.1)
        usage.add(result, f"repair {n}.{attempt}")
        edits, problems = parse_edits(result.text)
        if not problems:
            outside = sorted({normalize_path(e.path) for e in edits if normalize_path(e.path) not in allowed})
            if outside:  # the approval gate: a repair may not touch anything the person did not approve
                for path in outside:
                    record["needs_approval"].append({"path": path, "attempt": n,
                                                     "reason": "the repair needs to change a file that is not in the approved plan"})
                raise Stop("the repair needs to edit a file outside the approved plan: " + ", ".join(outside) +
                           ". Approve that change (add it to the plan) or fix it yourself.")
            weak = [e.path for e in edits if weakens_test(e.path, e.search, e.replace)]
            if weak:
                problems = [f"{w}: removing or loosening test assertions is not allowed" for w in weak]
        if not problems:
            try:
                step = apply_step(tree, edits, allowed, ctx.settings.max_file_bytes, f"repair {n}")
                s.store.append(run["id"], "changes", {**step, "repair": n, "at": now()})
                return [f["path"] for f in step["files"]], step["checkpoint"]
            except EditError as e:
                problems = e.problems
        problem = "; ".join(problems)
        convo += [ChatMessage("assistant", result.text),
                  ChatMessage("user", f"Your edits were refused: {problem}\nReply again with ONLY corrected edit blocks.")]
    raise Stop(f"the model could not produce an acceptable edit ({problem[:160]})")


def repair_loop(ctx, tree, run: dict) -> dict:
    settings, s = ctx.settings, ctx.services
    verification = run["verification"]
    commands = verification["commands"]
    profile = run.get("profile") or {}
    allowed = approved_files(run)
    record = {"attempts": [], "needs_approval": [], "stopped": None, "diagnostics": [], "usage": None}
    usage = UsageTracker(limit_tokens=settings.llm_num_ctx * 3 * max(1, settings.max_repair_attempts) * EDIT_TRIES)
    started = time.monotonic()
    last = verification["attempts"][-1]
    before = None
    try:
        for n in range(1, settings.max_repair_attempts + 1):
            ctx.check_cancel()
            if time.monotonic() - started > settings.repair_seconds:
                raise Stop(f"the repair time limit ({settings.repair_seconds}s) was reached")
            fails = failures_of(last)
            blockers = [f for f in fails if f["kind"] in ("environment", "timeout")]
            if blockers:
                raise Stop(f"this needs a person, not a code change ({blockers[0]['kind']}): {blockers[0]['message'][:200]}")
            if not fails:
                raise Stop("verification failed but no readable error was found: " + describe(last))
            signatures = sorted({f["signature"] for f in fails})
            if signatures == before:
                raise Stop("the same errors came back after the last repair, so repeating would not help")
            before = signatures
            targets = pick_targets(fails)
            entry = {"n": n, "targets": [{"signature": t["signature"], "kind": t["kind"], "file": t["file"],
                                           "line": t["line"], "message": t["message"][:200]} for t in targets],
                     "edits": [], "checkpoint": None, "result": "pending"}
            record["attempts"].append(entry)
            ctx.save(repair=record)
            try:
                entry["edits"], entry["checkpoint"] = _one_repair(ctx, tree, ctx.run, targets, allowed, n, usage, record)
            except AgentError as e:
                raise Stop(e.message) from e
            record["usage"] = usage.summary()
            affected = _failed_commands(last) or commands
            number = len(ctx.run["verification"]["attempts"]) + 1
            partial = verify_once(ctx, tree, profile, affected, number)
            partial["partial"], partial["after_repair"] = True, n
            attempts = ctx.run["verification"]["attempts"] + [partial]
            current = partial
            if partial["ok"] and set(affected) != set(commands):
                full = verify_once(ctx, tree, profile, commands, number + 1)  # then the WHOLE list, once
                full["after_repair"] = n
                attempts.append(full)
                current = full
            ctx.save(verification={"commands": commands, "attempts": attempts, "ok": current["ok"]})
            entry["result"] = "fixed" if current["ok"] else f"still failing: {describe(current)[:160]}"
            ctx.save(repair=record)
            last = current
            if current["ok"]:
                break
        else:
            raise Stop(f"{settings.max_repair_attempts} repair attempt(s) were used and checks still fail")
    except Stop as stop:
        record["stopped"] = stop.reason
    except AgentError as e:  # model unreachable, token budget ...
        record["stopped"] = e.message
    record["ok"] = last["ok"]
    record["usage"] = usage.summary()
    if not last["ok"]:
        record["diagnostics"] = [record["stopped"] or "unknown", describe(last), *[
            f"{f['kind']}: {f['file'] or f['command']}:{f['line'] or ''} {f['message'][:140]}" for f in failures_of(last)[:5]]]
    return record