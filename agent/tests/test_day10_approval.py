import shutil
import time
import unittest

from app.core.errors import AgentError
from app.deps import Services
from app.orchestrator.runner import Orchestrator
from app.orchestrator.store import RunStore
from app.planner import approval
from tests.helpers import FakeBackend, make_project, make_settings
from tests.test_day7_profile import POM

SVC = "backend/src/main/java/com/demo/review/ReviewService.java"


class ApprovalTests(unittest.TestCase):
    def setUp(self):
        self.root = make_project()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        (self.root / "backend/pom.xml").write_text(POM, encoding="utf-8")
        s = make_settings(use_llm=False)
        self.svc = Services(s, backend=FakeBackend(self.root), store=RunStore(s.data_dir))
        Orchestrator(self.svc).submit(1, 1, "Add star rating to reviews", None)
        for _ in range(200):
            if self.svc.store.get(1)["status"] not in ("QUEUED", "RUNNING"):
                break
            time.sleep(0.05)
        self.run = self.svc.store.get(1)
        self.assertEqual(self.run["status"], "AWAITING_APPROVAL", self.run["error"])

    def steps_of(self, run=None):
        return [{k: s[k] for k in ("title", "description", "action", "files", "depends_on", "verify")}
                for s in (run or self.svc.store.get(1))["plan"]["steps"]]

    def test_approve_current_version(self):
        done = approval.approve(self.svc, 1, 1, [], "tester", "looks right")
        self.assertEqual(done["status"], "APPROVED")
        self.assertEqual(done["approvals"][0]["by"], "tester")
        self.assertEqual(done["approvals"][0]["decision"], "APPROVED")

    def test_cannot_approve_a_stale_version(self):
        approval.edit_plan(self.svc, 1, self.steps_of(), None, "tester")  # version 2 now
        with self.assertRaises(AgentError) as ctx:
            approval.approve(self.svc, 1, 1, [], "tester")
        self.assertIn("version 2", ctx.exception.message)

    def test_edit_creates_a_new_version_and_keeps_the_old(self):
        steps = self.steps_of()[:1] + self.steps_of()[-1:]
        plan = approval.edit_plan(self.svc, 1, steps, "Smaller plan", "tester")
        run = self.svc.store.get(1)
        self.assertEqual((plan["version"], plan["generated_by"]), (2, "edited by tester"))
        self.assertEqual([p["version"] for p in run["plans"]], [1, 2])
        self.assertIn("version 2", self.svc.store.read_text(1, "plan.md"))
        self.assertEqual(run["status"], "AWAITING_APPROVAL")

    def test_invalid_edit_is_saved_but_blocks_approval(self):
        bad = self.steps_of()
        bad[0].update(action="modify", files=["does/not/Exist.java"])
        plan = approval.edit_plan(self.svc, 1, bad, None, "tester")
        self.assertTrue(plan["problems"])
        with self.assertRaises(AgentError) as ctx:
            approval.approve(self.svc, 1, plan["version"], [], "tester")
        self.assertIn("problems", ctx.exception.message)

    def test_risky_steps_must_be_acknowledged(self):
        steps = self.steps_of()
        steps.insert(1, {"title": "Remove old service", "description": "", "action": "delete",
                         "files": [SVC.replace("Service", "Controller")], "depends_on": ["s1"], "verify": None})
        plan = approval.edit_plan(self.svc, 1, steps, None, "tester")
        self.assertEqual(plan["problems"], [], plan["problems"])
        needed = [a["step"] for a in plan["approval_required"]]
        self.assertTrue(needed)
        with self.assertRaises(AgentError) as ctx:
            approval.approve(self.svc, 1, plan["version"], [], "tester")
        self.assertIn("must be acknowledged", ctx.exception.message)
        done = approval.approve(self.svc, 1, plan["version"], needed, "tester")
        self.assertEqual(done["status"], "APPROVED")
        self.assertEqual(done["approvals"][0]["acknowledged"], sorted(needed))

    def test_reject_needs_a_reason_and_is_final(self):
        with self.assertRaises(AgentError):
            approval.reject(self.svc, 1, "  ", "tester")
        done = approval.reject(self.svc, 1, "Wrong approach", "tester")
        self.assertEqual((done["status"], done["error"]), ("REJECTED", "Wrong approach"))
        with self.assertRaises(AgentError):
            approval.approve(self.svc, 1, 1, [], "tester")   # a decided run cannot be approved afterwards

    def test_only_a_run_awaiting_approval_can_change(self):
        approval.approve(self.svc, 1, 1, [], "tester")
        with self.assertRaises(AgentError):
            approval.edit_plan(self.svc, 1, self.steps_of(), None, "tester")


if __name__ == "__main__":
    unittest.main()