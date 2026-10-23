import shutil
import time
import unittest
from pathlib import Path

from app.deps import Services
from app.implement.context import build_step_prompt, outline
from app.implement.migrations import migration_note
from app.llm.mock import MockLLM
from app.orchestrator.runner import Orchestrator
from app.orchestrator.stages import StageContext
from app.orchestrator.store import RunStore
from app.planner import approval
from app.sandbox import workspace as ws
from tests.helpers import FakeBackend, make_project, make_settings
from tests.test_day7_profile import POM
from tests.test_day11_sandbox import wait_for

SVC = "backend/src/main/java/com/demo/review/ReviewService.java"
NEW = "backend/src/main/java/com/demo/review/Rating.java"
CTRL = "backend/src/main/java/com/demo/review/ReviewController.java"


def blocks(path, search, replace):
    return f"FILE: {path}\n<<<<<<< SEARCH\n{search}\n=======\n{replace}\n>>>>>>> REPLACE\n"


GOOD_MODIFY = blocks(SVC, "return stars;", "return Math.max(1, Math.min(5, stars));")
GOOD_CREATE = blocks(NEW, "", "package com.demo.review;\n\npublic class Rating {\n}")


def step(title, action, files, **kw):
    return dict({"title": title, "description": "do it", "action": action, "files": files, "depends_on": None,
                 "verify": None}, **kw)


def planned_run(testcase, steps, replies, **settings):
    """A run that is approved with exactly `steps`, with a scripted model."""
    root = make_project()
    testcase.addCleanup(shutil.rmtree, root, ignore_errors=True)
    (root / "backend/pom.xml").write_text(POM, encoding="utf-8")
    s = make_settings(use_llm=False, **settings)
    testcase.addCleanup(shutil.rmtree, s.data_dir, ignore_errors=True)
    llm = MockLLM([])
    svc = Services(s, backend=FakeBackend(root), llm=llm, store=RunStore(s.data_dir))
    orc = Orchestrator(svc)
    orc.submit(1, 1, "Add star rating to reviews", None)
    wait_for(svc, ("AWAITING_APPROVAL", "FAILED"))
    plan = approval.edit_plan(svc, 1, steps, None, "tester")
    assert plan["problems"] == [], plan["problems"]
    approval.approve(svc, 1, plan["version"], [a["step"] for a in plan["approval_required"]], "tester")
    llm.replies.extend(replies)
    return svc, orc, llm, root


STEPS = [step("Clamp the rating", "modify", [SVC]), step("Add the Rating type", "create", [NEW]),
         step("Verify", "verify", [], verify="backend:compile")]
NO_VERIFY = STEPS[:2]   # Day 13 tests stop after implementing; Day 14 adds the verification stage


class ContextTests(unittest.TestCase):
    def test_outline_keeps_declarations_only(self):
        text = "package a;\n\n@Service\npublic class A {\n    private int x = 1;\n    public int f(int a) {\n        return a;\n    }\n}\n"
        out = outline(text)
        self.assertIn("public class A {", out)
        self.assertIn("public int f(int a) {", out)
        self.assertNotIn("return a;", out)

    def test_prompt_contains_the_file_and_fits_the_window(self):
        svc, orc, llm, root = planned_run(self, NO_VERIFY, [])
        orc.start_implementation(1)
        wait_for(svc, ("IMPLEMENTED", "PARTIAL", "FAILED"))
        run = svc.store.get(1)
        tree = Path(run["workspace"]["path"])
        prompt = build_step_prompt(svc.settings, run, run["plan"]["steps"][0], tree, [], ws.__dict__ and [])
        self.assertIn("=== " + SVC, prompt.text)
        self.assertIn("public int rate(int stars)", prompt.text)
        self.assertLess(len(prompt.text) // 4, svc.settings.prompt_budget_tokens)

    def test_big_files_are_windowed(self):
        root = make_project()
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        big = "\n".join(f"line {i}" for i in range(1, 3000))
        (root / "Big.java").write_text(big, encoding="utf-8")
        s = make_settings()
        run = {"command": "x", "understanding": {}, "profile": {}, "candidates": [{"path": "Big.java", "ranges": [[1500, 1502]]}]}
        p = build_step_prompt(s, run, step("t", "modify", ["Big.java"]), root, [], [])
        self.assertIn("line 1501", p.text)
        self.assertNotIn("line 100\n", p.text)
        self.assertIn("(partial:", p.text)
        self.assertLess(len(p.text) // 4, s.prompt_budget_tokens + 50)

    def test_new_file_gets_a_style_example_from_its_folder(self):
        root = make_project()
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        p = build_step_prompt(make_settings(), {"command": "x", "understanding": {}, "profile": {}},
                              step("t", "create", [NEW]), root, [], [SVC, CTRL])
        self.assertIn("(new file", p.text)
        self.assertIn("Style example", p.text)
        self.assertIn("public class ReviewService", p.text)


class MigrationTests(unittest.TestCase):
    def test_no_tool_means_hibernate_note(self):
        n = migration_note({"modules": [{"dependencies": ["spring-boot-starter-data-jpa"]}]}, ["a/Review.java", "a/ReviewEntity.java"], [])
        self.assertIsNone(n)  # neither path is an entity by name alone
        n = migration_note({"modules": []}, ["db/V1__x.sql"], [])
        self.assertIn("Hibernate", n["message"])

    def test_flyway_next_file(self):
        n = migration_note({"modules": [{"dependencies": ["flyway-core"]}]}, ["db/V9__y.sql"],
                           ["src/main/resources/db/migration/V1__init.sql", "src/main/resources/db/migration/V4__x.sql"])
        self.assertEqual(n["next_file"], "src/main/resources/db/migration/V5__describe_the_change.sql")


class ExecutorTests(unittest.TestCase):
    def finish(self, svc, orc):
        orc.start_implementation(1)
        return wait_for(svc, ("IMPLEMENTED", "PARTIAL", "FAILED", "APPROVED_NEVER"))

    def test_plan_is_carried_out_step_by_step(self):
        svc, orc, llm, root = planned_run(self, NO_VERIFY, [GOOD_MODIFY, GOOD_CREATE])
        run = self.finish(svc, orc)
        self.assertEqual(run["status"], "IMPLEMENTED", run["error"])
        self.assertEqual([x["status"] for x in run["implementation"]["steps"]], ["DONE", "DONE"])
        self.assertEqual(len(run["changes"]), 2)
        tree = Path(run["workspace"]["path"])
        self.assertIn("Math.min(5", (tree / SVC).read_text(encoding="utf-8"))
        self.assertTrue((tree / NEW).is_file())
        self.assertEqual({c["path"] for c in ws.changed_files(tree, run["workspace"]["baseline"])}, {SVC, NEW})
        self.assertNotIn("Math.min", (root / SVC).read_text(encoding="utf-8"))   # your real project is untouched
        self.assertEqual(run["implementation"]["usage"]["calls"], 2)

    def test_a_refused_edit_is_retried_with_the_reason(self):
        bad = blocks(SVC, "return NOTHING;", "return 5;")
        svc, orc, llm, root = planned_run(self, STEPS[:1], [bad, GOOD_MODIFY])
        run = self.finish(svc, orc)
        self.assertEqual(run["status"], "IMPLEMENTED", run["error"])
        self.assertEqual(run["implementation"]["steps"][0]["attempts"], 2)
        self.assertIn("Your edits were refused", llm.calls[1][-1].content)
        self.assertIn("not found", llm.calls[1][-1].content)

    def test_two_bad_attempts_stop_the_run_cleanly(self):
        bad = blocks(SVC, "return NOTHING;", "return 5;")
        svc, orc, llm, root = planned_run(self, NO_VERIFY, [bad, bad])
        run = self.finish(svc, orc)
        self.assertEqual(run["status"], "PARTIAL")
        self.assertIn("s1", run["stage"])
        self.assertEqual(run["implementation"]["steps"][0]["status"], "FAILED")
        self.assertEqual(len(run["implementation"]["steps"]), 1)           # it did not go on to step 2
        tree = Path(run["workspace"]["path"])
        self.assertEqual(ws.changed_files(tree, run["workspace"]["baseline"]), [])

    def test_edits_outside_the_step_are_refused(self):
        outside = blocks(CTRL, "ReviewService s;", "ReviewService s2;")
        svc, orc, llm, root = planned_run(self, STEPS[:1], [outside, outside])
        run = self.finish(svc, orc)
        self.assertEqual(run["status"], "PARTIAL")
        self.assertIn("not part of the approved plan", run["implementation"]["steps"][0]["problem"])

    def test_unreadable_model_output_is_refused(self):
        svc, orc, llm, root = planned_run(self, STEPS[:1], ["I would change the service.", "Still just words."])
        run = self.finish(svc, orc)
        self.assertEqual(run["status"], "PARTIAL")
        self.assertIn("no edit blocks", run["implementation"]["steps"][0]["problem"])

    def test_resume_keeps_finished_steps(self):
        svc, orc, llm, root = planned_run(self, NO_VERIFY, [GOOD_MODIFY, "words", "words"])
        run = self.finish(svc, orc)
        self.assertEqual(run["status"], "PARTIAL")
        llm.replies.append(GOOD_CREATE)
        # the person just starts the implementation again: only the failed step is retried
        run = self.finish(svc, orc)
        self.assertEqual(run["status"], "IMPLEMENTED", run["error"])
        self.assertEqual([x["status"] for x in run["implementation"]["steps"]], ["DONE", "DONE"])
        self.assertEqual(run["implementation"]["steps"][0]["attempts"], 1)  # step 1 was not redone

    def test_delete_steps_need_no_model(self):
        steps = [step("Remove the controller", "delete", [CTRL]), step("Clamp", "modify", [SVC])]
        svc, orc, llm, root = planned_run(self, steps, [GOOD_MODIFY])
        run = self.finish(svc, orc)
        self.assertEqual(run["status"], "IMPLEMENTED", run["error"])
        self.assertEqual(len(llm.calls), 1)
        self.assertFalse((Path(run["workspace"]["path"]) / CTRL).exists())

    def test_migration_note_for_entity_changes(self):
        entity = "backend/src/main/java/com/demo/review/Review.java"
        svc, orc, llm, root = planned_run(self, [step("Rating entity", "create", ["backend/src/main/java/com/demo/db/V7__rating.sql"])],
                                          [blocks("backend/src/main/java/com/demo/db/V7__rating.sql", "", "create table rating(id int);")])
        run = self.finish(svc, orc)
        self.assertEqual(run["status"], "IMPLEMENTED", run["error"])
        self.assertIn("migration tool", run["implementation"]["migration"]["message"])


if __name__ == "__main__":
    unittest.main()