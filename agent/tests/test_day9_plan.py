import json
import shutil
import time
import unittest

from app.deps import Services
from app.llm.mock import MockLLM
from app.orchestrator.runner import Orchestrator
from app.orchestrator.store import RunStore
from app.planner.plan import compute_approvals, finalize, normalize_plan, plan_to_markdown, template_plan, validate_plan
from app.planner.plan_llm import build_prompt, generate_plan
from tests.helpers import FakeBackend, make_project, make_settings
from tests.test_day7_profile import PKG, POM

SVC = "backend/src/main/java/com/demo/review/ReviewService.java"
CTRL = "backend/src/main/java/com/demo/review/ReviewController.java"
FILES = {SVC, CTRL, "backend/pom.xml", "frontend/src/components/StarRating.jsx"}
COMMANDS = {"backend:compile", "backend:test", "frontend:build"}
PROFILE = {"modules": [{"label": "backend", "dir": "backend", "kind": "maven", "frameworks": []},
                       {"label": "frontend", "dir": "frontend", "kind": "node", "frameworks": []}],
           "commands": {k: {"available": True} for k in COMMANDS},
           "java": {"module_dir": "backend", "base_dir": "com/demo", "base_package": "com.demo", "layout": "by-feature",
                    "test_suffix": "Test"}}
UNDERSTANDING = {"command": "Add star rating", "summary": "Users rate reviews 1 to 5",
                 "acceptance_criteria": ["rating must be between 1 and 5"], "assumptions": []}
IMPACT = {"components": [
    {"path": SVC, "layer": "service", "role": "modify", "score": 0.9, "why": "ranked match"},
    {"path": CTRL, "layer": "controller", "role": "modify", "score": 0.6, "why": "uses service"},
    {"path": "frontend/src/components/StarRating.jsx", "layer": "ui-component", "role": "modify", "score": 0.5, "why": "name"}],
    "suggested_new_files": [], "risks": ["No existing test found for x"], "layers_touched": ["service", "controller"]}


def raw(*steps, **kw):
    return dict({"summary": "s", "steps": list(steps)}, **kw)


def step(title, action="modify", files=(), **kw):
    return dict({"title": title, "action": action, "files": list(files), "description": "d"}, **kw)


class ValidateTests(unittest.TestCase):
    def check(self, steps):
        return validate_plan(normalize_plan(raw(*steps), 1, "x"), FILES, COMMANDS)

    def test_good_plan(self):
        self.assertEqual(self.check([step("a", files=[SVC]), step("v", "verify", verify="backend:compile")]), [])

    def test_each_rule(self):
        self.assertTrue(any("does not exist" in p for p in self.check([step("a", files=["nope/X.java"])])))
        self.assertTrue(any("already exists" in p for p in self.check([step("a", "create", [SVC])])))
        self.assertTrue(any("relative path" in p for p in self.check([step("a", "create", ["../../etc/x"])])))
        self.assertTrue(any("ignored or secret" in p for p in self.check([step("a", "create", [".env"])])))
        self.assertTrue(any("verify must be" in p for p in self.check([step("a", files=[SVC]), step("v", "verify", verify="rm -rf")])))
        self.assertTrue(any("not an earlier step" in p for p in self.check([step("a", files=[SVC], depends_on=[2]), step("b", files=[CTRL])])))
        self.assertTrue(any("first step cannot" in p for p in self.check([step("v", "verify", verify="backend:test"), step("a", files=[SVC])])))
        self.assertTrue(any("1 to 12" in p for p in self.check([])))
        self.assertTrue(any("at least one file" in p for p in self.check([step("a", files=[])])))

    def test_depends_on_forms_are_normalised(self):
        plan = normalize_plan(raw(step("a", files=[SVC]), step("b", files=[CTRL], depends_on=["s1"]),
                                  step("c", files=[CTRL], depends_on=[1, 2])), 1, "x")
        self.assertEqual([s["depends_on"] for s in plan["steps"]], [[], ["s1"], ["s1", "s2"]])


class ApprovalTests(unittest.TestCase):
    def test_flags_risky_steps(self):
        plan = normalize_plan(raw(step("a", "delete", [SVC]), step("b", files=["backend/pom.xml"]),
                                  step("c", "create", ["backend/src/main/java/com/demo/x/Thing.java"])), 1, "x")
        kinds = {a["kind"] for a in compute_approvals(plan)}
        self.assertEqual(kinds, {"delete", "dependency"})

    def test_entity_means_database(self):
        plan = normalize_plan(raw(step("a", "create", ["backend/src/main/resources/db/V1__x.sql"])), 1, "x")
        self.assertEqual(compute_approvals(plan)[0]["kind"], "database")


class TemplateTests(unittest.TestCase):
    def test_template_is_valid_and_ordered(self):
        plan = finalize(normalize_plan(template_plan(UNDERSTANDING, IMPACT, PROFILE), 1, "template"), FILES, COMMANDS)
        self.assertEqual(plan["problems"], [])
        actions = [s["action"] for s in plan["steps"]]
        layers = [s["title"] for s in plan["steps"]]
        self.assertLess(layers.index("Update the service layer"), layers.index("Update the controller layer"))
        self.assertEqual(actions[-1], "verify")
        self.assertIn("backend:compile", plan["commands"])
        self.assertTrue(any("Add tests" in t for t in layers))          # modified files without a test get one
        self.assertTrue(all(t.endswith("Test.java") for t in plan["tests_to_add"]))


class GenerateTests(unittest.TestCase):
    RUN = {"understanding": UNDERSTANDING, "impact": IMPACT, "profile": PROFILE}

    def gen(self, replies, **settings):
        s = make_settings(**settings)
        llm = MockLLM([json.dumps(r) if isinstance(r, dict) else r for r in replies])
        return generate_plan(s, llm, self.RUN, list(FILES)), llm

    def test_good_model_plan_is_used_and_verification_is_added(self):
        plan, _ = self.gen([raw(step("Service", files=[SVC]), step("Controller", files=[CTRL], depends_on=[1]))])
        self.assertEqual(plan["generated_by"], "llm")
        self.assertEqual(plan["problems"], [])
        self.assertEqual(plan["steps"][-1]["action"], "verify")          # the model forgot; the agent added it

    def test_bad_model_plan_is_repaired_once_then_falls_back(self):
        bad = raw(step("x", files=["invented/File.java"]))
        plan, llm = self.gen([bad, bad])
        self.assertEqual(plan["generated_by"], "template")
        self.assertIn("did not return valid JSON", plan["fallback_reason"])
        self.assertIn("does not exist", llm.calls[1][-1].content)         # the model was told what was wrong
        self.assertEqual(plan["problems"], [])

    def test_second_attempt_can_succeed(self):
        plan, _ = self.gen([raw(step("x", files=["invented/File.java"])), raw(step("ok", files=[SVC]))])
        self.assertEqual(plan["generated_by"], "llm")

    def test_llm_off(self):
        plan, llm = self.gen([], use_llm=False)
        self.assertEqual(plan["generated_by"], "template")
        self.assertEqual(llm.calls, [])

    def test_prompt_fits_a_small_model(self):
        prompt = build_prompt(UNDERSTANDING, IMPACT, PROFILE, sorted(COMMANDS))
        self.assertLess(len(prompt.text) // 4, make_settings().prompt_budget_tokens)
        self.assertIn(SVC, prompt.text)

    def test_markdown(self):
        plan, _ = self.gen([], use_llm=False)
        md = plan_to_markdown(plan, UNDERSTANDING)
        self.assertIn("# Implementation plan (version 1, template)", md)
        self.assertIn("## Acceptance criteria", md)
        self.assertIn("`backend:compile`", md)


class StageTests(unittest.TestCase):
    def test_run_ends_awaiting_approval_with_a_plan(self):
        root = make_project()
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        (root / "backend/pom.xml").write_text(POM, encoding="utf-8")
        s = make_settings(use_llm=False)
        svc = Services(s, backend=FakeBackend(root), store=RunStore(s.data_dir))
        Orchestrator(svc).submit(1, 1, "Add star rating to reviews", None)
        for _ in range(200):
            run = svc.store.get(1)
            if run["status"] not in ("QUEUED", "RUNNING"):
                break
            time.sleep(0.05)
        self.assertEqual(run["status"], "AWAITING_APPROVAL", run["error"])
        self.assertTrue(run["plan"]["steps"])
        self.assertEqual(run["plans"][0]["version"], 1)
        self.assertEqual([x["stage"] for x in run["steps"]],
                         ["Profile", "Understand", "Discover", "Impact", "Pack", "Plan"])
        self.assertIn("Implementation plan", svc.store.read_text(1, "plan.md"))


if __name__ == "__main__":
    unittest.main()