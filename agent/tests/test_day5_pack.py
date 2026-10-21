import shutil
import time
import unittest

from app.context import pipeline
from app.context.expander import Candidate
from app.context.pack import build_pack
from app.context.testmap import build_test_index, config_files, is_test_path, tests_for
from app.context.understand import understand
from app.deps import Services
from app.orchestrator.runner import Orchestrator
from app.orchestrator.store import RunStore
from tests.helpers import FakeBackend, make_project, make_settings

SERVICE = "backend/src/main/java/com/demo/review/ReviewService.java"
CONTROLLER = "backend/src/main/java/com/demo/review/ReviewController.java"
TEST = "backend/src/test/java/com/demo/review/ReviewServiceTest.java"


class MappingTests(unittest.TestCase):
    def test_test_detection_and_mapping(self):
        files = [SERVICE, TEST, "frontend/src/Star.jsx", "frontend/src/Star.test.jsx", "api/test_user.py", "api/user.py"]
        index = build_test_index(files)
        self.assertTrue(is_test_path(TEST))
        self.assertFalse(is_test_path(SERVICE))
        self.assertEqual(tests_for(SERVICE, index), [TEST])
        self.assertEqual(tests_for("frontend/src/Star.jsx", index), ["frontend/src/Star.test.jsx"])
        self.assertEqual(tests_for("api/user.py", index), ["api/test_user.py"])

    def test_config_files(self):
        found = config_files(["pom.xml", "backend/pom.xml", "backend/src/main/resources/application.properties", "a/b/c/pom.xml"])
        self.assertEqual(found, ["pom.xml", "backend/pom.xml", "backend/src/main/resources/application.properties"])

    def test_understand(self):
        u = understand("Add a rating feature to the React page and a database column")
        self.assertIn("frontend", u.areas)
        self.assertIn("database", u.areas)
        self.assertIn("rating", u.keywords)


class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.root = make_project()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.settings = make_settings()
        self.backend = FakeBackend(self.root)

    def test_context_pack_has_complete_files_tests_and_neighbours(self):
        u, found, pack = pipeline.run_all(self.settings, self.backend, 1, "Add star rating to reviews", None)
        paths = {i.path: i for i in pack.items}
        self.assertEqual(paths[SERVICE].level, "FULL")           # complete file, not a chunk
        self.assertIn("public int rate", paths[SERVICE].text)
        self.assertIn(CONTROLLER, paths)                           # graph neighbour (a dependent)
        self.assertIn(TEST, paths)                                 # the test that covers the service
        self.assertTrue(any("not found" in n for n in pack.not_included + pack.notes))  # stale 'gone/Deleted.java'
        self.assertNotIn("hunter2", pack.markdown)
        self.assertTrue(pack.stats()["withinBudget"])
        self.assertIn("## " + SERVICE, pack.markdown)

    def test_scores_put_best_match_first(self):
        _, found, _ = pipeline.run_all(self.settings, self.backend, 1, "Add star rating", None)
        self.assertEqual(found.candidates[0].path, SERVICE)

    def test_small_budget_degrades_to_windows_and_paths(self):
        big = Candidate("backend/src/main/java/com/demo/review/ReviewService.java", 0.9, ["x"], {"rank"}, [(4, 5)])
        pack = build_pack(self.root, [big], "t", 500, 10_000, {}, [])
        self.assertIn(pack.items[0].level, {"FULL", "WINDOW", "PATH_ONLY"})
        tiny = build_pack(self.root, [big], "t", 500, 10_000, {}, [])
        self.assertLessEqual(tiny.pack_tokens, 500 + 150)

    def test_windowing_for_large_files(self):
        (self.root / "Big.java").write_text("\n".join(f"line {i}" for i in range(1, 2000)), encoding="utf-8")
        pack = build_pack(self.root, [Candidate("Big.java", 0.9, ["x"], {"rank"}, [(1000, 1002)])], "t", 2000, 300_000, {}, [])
        self.assertEqual(pack.items[0].level, "WINDOW")
        self.assertIn("line 1001", pack.items[0].text)
        self.assertNotIn("line 1500", pack.items[0].text)

    def test_fence_survives_backticks_in_code(self):
        (self.root / "Doc.md").write_text("```js\nx\n```\n", encoding="utf-8")
        pack = build_pack(self.root, [Candidate("Doc.md", 0.9, ["x"], {"rank"})], "t", 6000, 300_000, {}, [])
        self.assertIn("````markdown", pack.markdown)


class OrchestratorTests(unittest.TestCase):
    def setUp(self):
        self.root = make_project()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.settings = make_settings()
        self.store = RunStore(self.settings.data_dir)
        self.services = Services(self.settings, backend=FakeBackend(self.root), store=self.store)
        self.orc = Orchestrator(self.services)

    def wait(self, run_id, done=("CONTEXT_READY", "AWAITING_APPROVAL", "NEEDS_INPUT", "FAILED", "CANCELLED")):
        for _ in range(100):
            run = self.store.get(run_id)
            if run["status"] in done:
                return run
            time.sleep(0.05)
        self.fail("run did not finish")

    def test_full_run_records_steps_and_pack(self):
        self.orc.submit(7, 1, "Add star rating to reviews", None)
        run = self.wait(7)
        self.assertIn(run["status"], ("CONTEXT_READY", "AWAITING_APPROVAL"))  # later days add a plan stage
        done = [s["stage"] for s in run["steps"] if s["status"] == "DONE"]
        self.assertEqual([d for d in done if d in ("Understand", "Discover", "Pack")], ["Understand", "Discover", "Pack"])
        self.assertTrue(all(s["status"] == "DONE" for s in run["steps"]))
        self.assertIn("ReviewService.java", self.store.pack_markdown(7))
        self.assertGreater(run["pack"]["stats"]["fullFiles"], 0)

    def test_backend_failure_is_reported_not_swallowed(self):
        class Broken(FakeBackend):
            def project(self, project_id):
                from app.core.errors import BackendUnavailable
                raise BackendUnavailable("Spring backend not reachable")

        orc = Orchestrator(Services(self.settings, backend=Broken(self.root), store=self.store))
        orc.submit(8, 1, "anything goes here", None)
        run = self.wait(8)
        self.assertEqual(run["status"], "FAILED")
        self.assertIn("not reachable", run["error"])
        self.assertEqual(run["steps"][-1]["status"], "FAILED")

    def test_cancel_queued_run(self):
        self.orc.pool.submit(time.sleep, 0.3)  # keep the single worker busy so run 9 stays queued
        self.orc.submit(9, 1, "Add star rating to reviews", None)
        self.assertEqual(self.orc.cancel(9)["status"], "CANCELLED")
        time.sleep(0.5)
        self.assertEqual(self.store.get(9)["status"], "CANCELLED")

    def test_restart_marks_active_runs_interrupted(self):
        self.store.create(11, 1, "abc def", None)
        reopened = RunStore(self.settings.data_dir)
        self.assertEqual(reopened.get(11)["status"], "FAILED")
        self.assertEqual(reopened.get(11)["stage"], "Interrupted")


if __name__ == "__main__":
    unittest.main()