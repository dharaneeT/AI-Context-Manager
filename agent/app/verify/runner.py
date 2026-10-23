"""Runs the plan's verification commands in the workspace and turns the output into an evidence record.

"Passed" is only ever reported when the command really exited 0 AND no failure could be read from its output.
Nothing in this module (or the report built from it) says "tests passed" on the model's word."""
from __future__ import annotations

import time

from app.core.errors import AgentError
from app.orchestrator.store import now
from app.sandbox.runner import run_command
from app.verify.parsers import parse_result


def commands_from_plan(plan: dict) -> list:
    seen, out = set(), []
    for step in plan.get("steps", []):
        key = step.get("verify")
        if step["action"] == "verify" and key and key not in seen:
            seen.add(key)
            out.append(key)
    return out


def verify_once(ctx, tree, profile: dict, commands: list, number: int) -> dict:
    results, broken_modules = [], set()
    for key in commands:
        ctx.check_cancel()
        label, _, kind = key.partition(":")
        if label in broken_modules and kind in ("test", "lint"):
            results.append({"command": key, "passed": False, "skipped": True, "exit_code": None, "seconds": 0,
                            "failures": [], "summary": None, "warnings": [],
                            "reason": f"skipped: {label} does not build yet", "output_excerpt": ""})
            continue
        started = time.time()
        try:
            res = run_command(ctx.settings, tree, profile, key)
        except AgentError as e:  # refused (unavailable, not allowed): report it, never hide it
            res = {"command": key, "exit_code": -1, "timed_out": False, "seconds": 0.0, "output": e.message,
                   "output_truncated": False, "environment_problem": True}
        failures, summary, warnings = parse_result(res, profile, tree, started)
        passed = res["exit_code"] == 0 and not failures
        if not passed and kind in ("compile", "build"):
            broken_modules.add(label)
        results.append({"command": key, "passed": passed, "skipped": False, "exit_code": res["exit_code"],
                        "seconds": res["seconds"], "failures": failures[:20], "summary": summary, "warnings": warnings,
                        "output_excerpt": res["output"][-4000:] if not passed else res["output"][-300:],
                        "output_truncated": res["output_truncated"]})
    return {"n": number, "at": now(), "ok": bool(results) and all(r["passed"] for r in results), "results": results}


def failures_of(attempt: dict) -> list:
    return [f for r in attempt["results"] for f in r["failures"]]


def describe(attempt: dict) -> str:
    bad = [r for r in attempt["results"] if not r["passed"]]
    if not bad:
        return "all checks passed: " + ", ".join(r["command"] for r in attempt["results"])
    first = next((f for r in bad for f in r["failures"]), None)
    return f"{len(bad)} of {len(attempt['results'])} checks failed" + (
        f"; first: {first['kind']} in {first['file'] or first['command']}: {first['message'][:120]}" if first else "")
