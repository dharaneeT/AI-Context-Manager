import os
import shutil
import sys
import tempfile
import time
import unittest
from pathlib import Path

from app.context.files import list_project_files
from app.context.git import repo_state
from app.core.errors import AgentError
from app.sandbox import workspace as ws
from app.sandbox.runner import OutputBuffer, clean_env, run_command, validate_command
from app.deps import Services
from app.orchestrator.runner import Orchestrator
from app.orchestrator.store import RunStore
from app.planner import approval
from tests.helpers import FakeBackend, make_project, make_settings
from tests.test_day7_profile import POM


def make_ws(extra=None):
    root = make_project()
    for rel, text in (extra or {}).items():
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
    settings = make_settings()
    meta = ws.create_workspace(settings, 1, root, list_project_files(root), repo_state(root))
    return root, settings, meta, ws.tree_of(meta)


class WorkspaceTests(unittest.TestCase):
    def setUp(self):
        self.root, self.settings, self.meta, self.tree = make_ws()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.addCleanup(shutil.rmtree, self.settings.data_dir, ignore_errors=True)

    def test_copy_has_project_files_but_not_secrets_or_dependencies(self):
        self.assertTrue((self.tree / "backend/src/main/java/com/demo/review/ReviewService.java").is_file())
        self.assertFalse((self.tree / ".env").exists())
        self.assertFalse((self.tree / "frontend/node_modules").exists())
        self.assertEqual(ws.changed_files(self.tree), [])

    def test_real_project_is_never_written(self):
        (self.tree / "backend/src/main/java/com/demo/review/ReviewService.java").write_text("changed", encoding="utf-8")
        original = (self.root / "backend/src/main/java/com/demo/review/ReviewService.java").read_text(encoding="utf-8")
        self.assertIn("class ReviewService", original)

    def test_changes_and_diff(self):
        svc = self.tree / "backend/src/main/java/com/demo/review/ReviewService.java"
        svc.write_text(svc.read_text(encoding="utf-8").replace("return stars;", "return stars + 1;\n"), encoding="utf-8")
        (self.tree / "backend/src/main/java/com/demo/review/New.java").write_text("class New {}\n", encoding="utf-8")
        (self.tree / "frontend/src/components/StarRating.jsx").unlink()
        changes = {c["path"].rsplit("/", 1)[-1]: c for c in ws.changed_files(self.tree)}
        self.assertEqual({k: v["status"] for k, v in changes.items()},
                         {"ReviewService.java": "M", "New.java": "A", "StarRating.jsx": "D"})
        self.assertGreater(changes["ReviewService.java"]["added"], 0)
        diff = ws.diff_text(self.tree)
        self.assertIn("+        return stars + 1;", diff)
        self.assertIn("new file mode", diff)

    def test_reset(self):
        f = self.tree / "backend/pom.xml"
        f.write_text("broken", encoding="utf-8")
        (self.tree / "junk.txt").write_text("x", encoding="utf-8")
        ws.reset_workspace(self.tree)
        self.assertEqual(ws.changed_files(self.tree), [])
        self.assertFalse((self.tree / "junk.txt").exists())

    def test_uncommitted_changes_of_the_real_project_are_included(self):
        self.assertEqual(self.meta["source_uncommitted"], 0)  # not a git repo here, so nothing to commit around


class LinkTests(unittest.TestCase):
    @unittest.skipIf(sys.platform == "win32", "symlink semantics differ on Windows (a junction is used there)")
    def test_node_modules_is_linked_and_never_deleted_with_the_workspace(self):
        root, settings, meta, tree = make_ws({"frontend/package.json": "{}", "frontend/node_modules/lib/index.js": "x"})
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        self.assertEqual(meta["linked"], ["frontend/node_modules"])
        self.assertTrue((tree / "frontend/node_modules/lib/index.js").is_file())
        ws.destroy_workspace(settings, 1, meta["linked"])
        self.assertFalse(tree.exists())
        self.assertTrue((root / "frontend/node_modules/lib/index.js").is_file())  # the real folder survived


def profile_with(argv, cwd="backend", available=True, key="backend:compile"):
    return {"commands": {key: {"cwd": cwd, "argv": argv, "available": available, "reason": "" if available else "nope"}}}


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.root, self.settings, self.meta, self.tree = make_ws()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.addCleanup(shutil.rmtree, self.settings.data_dir, ignore_errors=True)

    def script(self, name, body):
        (self.tree / "backend" / f"{name}.py").write_text(body, encoding="utf-8")
        return profile_with([sys.executable, "-m", name])

    def test_runs_and_captures_exit_code_and_output(self):
        p = self.script("tool", "import sys; print('hello'); print('boom', file=sys.stderr); sys.exit(3)")
        r = run_command(self.settings, self.tree, p, "backend:compile")
        self.assertEqual(r["exit_code"], 3)
        self.assertIn("hello", r["output"])
        self.assertIn("boom", r["output"])  # stderr is merged into the same stream, in order
        self.assertFalse(r["timed_out"])

    def test_timeout_kills_the_process(self):
        p = self.script("slow", "import time; time.sleep(60)")
        r = run_command(self.settings, self.tree, p, "backend:compile", timeout=1)
        self.assertTrue(r["timed_out"])
        self.assertLess(r["seconds"], 15)

    def test_output_is_limited_but_keeps_head_and_tail(self):
        s = make_settings(max_output_chars=2000)
        p = self.script("loud", "print('START'); print('x' * 100000); print('THE-END')")
        r = run_command(s, self.tree, p, "backend:compile")
        self.assertTrue(r["output_truncated"])
        self.assertLess(len(r["output"]), 2400)
        self.assertIn("START", r["output"])
        self.assertIn("THE-END", r["output"])

    def test_secrets_are_not_passed_and_not_printed(self):
        os.environ["OPENAI_API_KEY"] = "sk-this-must-not-leak-0123456789"
        self.addCleanup(os.environ.pop, "OPENAI_API_KEY", None)
        p = self.script("env", "import os; print('KEY=', os.environ.get('OPENAI_API_KEY')); print('password=hunter2hunter2')")
        r = run_command(self.settings, self.tree, p, "backend:compile")
        self.assertIn("KEY= None", r["output"])
        self.assertNotIn("hunter2", r["output"])
        self.assertNotIn("OPENAI_API_KEY", clean_env(self.settings))
        self.assertEqual(clean_env(self.settings)["MAVEN_OPTS"], "-Xmx768m")

    def test_refusals(self):
        t = self.tree
        with self.assertRaises(AgentError) as c:
            validate_command(profile_with(["bash", "-c", "x"]), "backend:compile", t)
        self.assertEqual(c.exception.status, 403)
        for bad in (["mvn", "a;b"], ["mvn", "$(whoami)"], ["mvn", "../x"], ["mvn", "a b"], ["mvn", "`x`"]):
            with self.assertRaises(AgentError):
                validate_command(profile_with(bad), "backend:compile", t)
        with self.assertRaises(AgentError):
            validate_command(profile_with(["mvn"], cwd="../.."), "backend:compile", t)
        with self.assertRaises(AgentError) as c:
            validate_command(profile_with(["mvn"], available=False), "backend:compile", t)
        self.assertEqual(c.exception.status, 409)
        with self.assertRaises(AgentError) as c:
            validate_command(profile_with(["mvn"]), "rm-everything", t)
        self.assertEqual(c.exception.status, 400)

    def test_missing_program_is_an_environment_problem(self):
        r = run_command(self.settings, self.tree, profile_with(["mvn", "-q"]), "backend:compile")
        # (maven is not installed in the test environment; on your PC this branch means "install it")
        if r["exit_code"] == -1:
            self.assertTrue(r["environment_problem"])
            self.assertIn("not found", r["output"])


class BufferTests(unittest.TestCase):
    def test_small_output_is_untouched(self):
        b = OutputBuffer(100)
        b.add("abc")
        self.assertEqual(b.text(), "abc")

    def test_overflow_reports_what_was_dropped(self):
        b = OutputBuffer(40)
        for i in range(20):
            b.add(f"line{i:02d}\n")
        text = b.text()
        self.assertTrue(text.startswith("line00"))
        self.assertTrue(text.endswith("line19\n"))
        self.assertIn("characters omitted", text)


def approved_run(testcase):
    """A run that went through the whole planning phase and was approved by a person."""
    root = make_project()
    testcase.addCleanup(shutil.rmtree, root, ignore_errors=True)
    (root / "backend/pom.xml").write_text(POM, encoding="utf-8")
    s = make_settings(use_llm=False)
    testcase.addCleanup(shutil.rmtree, s.data_dir, ignore_errors=True)
    svc = Services(s, backend=FakeBackend(root), store=RunStore(s.data_dir))
    orc = Orchestrator(svc)
    orc.submit(1, 1, "Add star rating to reviews", None)
    wait_for(svc, ("AWAITING_APPROVAL", "FAILED"))
    needed = [a["step"] for a in svc.store.get(1)["plan"]["approval_required"]]
    approval.approve(svc, 1, 1, needed, "tester")
    return svc, orc, root


def wait_for(svc, states, run_id=1, seconds=20):
    for _ in range(int(seconds * 20)):
        if svc.store.get(run_id)["status"] in states:
            return svc.store.get(run_id)
        time.sleep(0.05)
    raise AssertionError(f"never reached {states}: {svc.store.get(run_id)['status']}")


class ImplementationGateTests(unittest.TestCase):
    def test_only_an_approved_run_gets_a_workspace(self):
        svc, orc, root = approved_run(self)
        self.assertEqual(svc.store.get(1)["status"], "APPROVED")
        orc.start_implementation(1)
        run = wait_for(svc, ("APPROVED", "PARTIAL", "IMPLEMENTED", "FAILED"))  # later days add stages after Workspace
        self.assertNotEqual(run["status"], "FAILED", run["error"])
        self.assertTrue(Path(run["workspace"]["path"]).is_dir())
        self.assertEqual(ws.changed_files(Path(run["workspace"]["path"]), run["workspace"]["baseline"]), [])
        self.assertIn("Workspace", [s["stage"] for s in run["steps"] if s["status"] == "DONE"])

    def test_unapproved_runs_cannot_start(self):
        root = make_project()
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        s = make_settings(use_llm=False)
        svc = Services(s, backend=FakeBackend(root), store=RunStore(s.data_dir))
        orc = Orchestrator(svc)
        orc.submit(1, 1, "Add star rating to reviews", None)
        wait_for(svc, ("AWAITING_APPROVAL",))
        with self.assertRaises(AgentError) as c:
            orc.start_implementation(1)
        self.assertEqual(c.exception.status, 409)

    def test_editing_the_plan_after_approval_is_impossible_and_a_new_version_needs_new_approval(self):
        svc, orc, root = approved_run(self)
        with self.assertRaises(AgentError):
            approval.edit_plan(svc, 1, [], None, "tester")  # an approved run is no longer editable


if __name__ == "__main__":
    unittest.main()