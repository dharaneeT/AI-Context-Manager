"""Schema changes. The agent never touches a real database. It only tells you what this project needs."""
from __future__ import annotations

import re

from app.planner.impact import classify

TOOLS = {"flyway-core": "Flyway", "liquibase-core": "Liquibase"}


def migration_note(profile: dict, changed_paths: list, all_files: list) -> dict | None:
    """If the data model changed, say how this project migrates its schema, and (Flyway) the next file name."""
    if not any(classify(p) in ("entity", "migration") for p in changed_paths):
        return None
    deps = {d for m in profile.get("modules", []) for d in m.get("dependencies", [])}
    tool = next((name for dep, name in TOOLS.items() if dep in deps), None)
    if tool == "Flyway":
        existing = [f for f in all_files if re.search(r"db/migration/V(\d+)__", f)]
        versions = [int(re.search(r"V(\d+)__", f).group(1)) for f in existing]
        folder = existing[0].rsplit("/", 1)[0] if existing else "src/main/resources/db/migration"
        return {"tool": tool, "next_file": f"{folder}/V{max(versions, default=0) + 1}__describe_the_change.sql",
                "message": "The data model changed. Add the next Flyway migration (not generated automatically yet) "
                           "and review it before running it on real data."}
    if tool:
        return {"tool": tool, "next_file": None,
                "message": "The data model changed. Add a Liquibase changeset and review it before running it on real data."}
    return {"tool": None, "next_file": None,
            "message": "The data model changed. No migration tool (Flyway or Liquibase) was found, so the schema is "
                       "probably created by Hibernate (ddl-auto) when the application starts. Check that setting "
                       "before running the new code against a database that holds real data."}