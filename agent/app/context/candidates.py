"""Merge search, ranking, graph, test and config evidence into one scored list of candidate files."""
from __future__ import annotations

from app.context.expander import Candidate, from_search, get_candidate
from app.context.testmap import CONFIG_TASK_WORDS, build_test_index, config_files, tests_for
from app.context.understand import Understanding

SEED_COUNT = 3          # how many top matches get their graph neighbours pulled in
NEIGHBOURS_PER_SEED = 6  # cap so a hub file cannot flood the list
TEST_SEEDS = 5


def add_graph_neighbours(cands: dict, backend, project_id: int, notes: list) -> None:
    """Step 2: follow imports. Files the best matches depend on (or that depend on them) must be understood too."""
    seeds = sorted(cands.values(), key=lambda c: -c.score)[:SEED_COUNT]
    for seed in seeds:
        try:
            deps = backend.dependencies(project_id, seed.path)[:NEIGHBOURS_PER_SEED]
            users = backend.dependents(project_id, seed.path)[:NEIGHBOURS_PER_SEED]
        except Exception as e:  # graph not built yet is not fatal
            notes.append(f"Graph unavailable for {seed.path}: {e}. Run POST /graph/build on the backend.")
            return
        for path in deps:
            get_candidate(cands, path).add(0.4 * seed.score, f"imported by {seed.path}", "graph")
        for path in users:
            get_candidate(cands, path).add(0.3 * seed.score, f"uses {seed.path}", "graph")


def add_tests_and_config(cands: dict, all_files: list, understanding: Understanding) -> None:
    """Step 3: tests that cover the best matches, and build/config files when the task touches them."""
    index = build_test_index(all_files)
    ranked = sorted(cands.values(), key=lambda c: -c.score)
    for c in ranked[:TEST_SEEDS]:
        for t in tests_for(c.path, index):
            get_candidate(cands, t).add(max(0.2, 0.55 * c.score), f"test for {c.path}", "test")
    wants_config = bool(CONFIG_TASK_WORDS.search(understanding.command)) or "config" in understanding.areas \
        or "database" in understanding.areas
    for path in config_files(all_files):
        get_candidate(cands, path).add(0.45 if wants_config else 0.08, "build/config file", "config")


def apply_area_hints(cands: dict, understanding: Understanding) -> None:
    """Step 4: small nudges when the task clearly names an area (frontend, database, tests)."""
    for c in cands.values():
        p = c.path.lower()
        if "frontend" in understanding.areas and (p.startswith("frontend/") or p.endswith((".jsx", ".tsx", ".css"))):
            c.add(0.08, "task mentions the frontend", "area")
        if "database" in understanding.areas and any(w in p for w in ("entity", "repository", ".sql", "model")):
            c.add(0.08, "task mentions the database", "area")
        if "tests" in understanding.areas and ("test" in p):
            c.add(0.08, "task mentions tests", "area")


def build_candidates(backend, project_id: int, command: str, understanding: Understanding, all_files: list,
                     notes: list, search_limit: int = 25) -> list:
    ranking = backend.rank(project_id, command, search_limit)
    hybrid = backend.hybrid_search(project_id, command, 15)
    if not ranking.get("semanticUsed", True):
        notes.append("Semantic search was not used (" + str(ranking.get("semanticNote") or "no embeddings") + ").")
    cands = from_search(ranking, hybrid)
    add_graph_neighbours(cands, backend, project_id, notes)
    add_tests_and_config(cands, all_files, understanding)
    apply_area_hints(cands, understanding)
    known = set(all_files)
    out = [c for c in cands.values() if c.path in known or not all_files]
    for c in cands.values():
        if c not in out:
            notes.append(f"Skipped {c.path}: not found on disk or ignored.")
    return sorted(out, key=lambda c: (-c.score, c.path))