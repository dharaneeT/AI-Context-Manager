import json
import shutil
import time
import unittest

from app.context.understand import Understanding
from app.core.errors import AgentError
from app.deps import Services
from app.llm.mock import MockLLM
from app.orchestrator.runner import Orchestrator
from app.orchestrator.store import RunStore
from app.planner.understanding import analyze, make_validator
from tests.helpers import FakeBackend, make_project, make_settings

GOOD = {"summary": "Users can rate a finished exchange from 1 to 5 stars.",
        "acceptance_criteria": ["A rating below 1 or above 5 is rejected", "A user can rate only once per exchange"],
        "constraints": ["Only participants may rate"], "assumptions": ["Ratings cannot be edited"],
        "areas": ["backend", "frontend", "bogus"], "needs_clarification": False, "questions": []}
ASKING = dict(GOOD, needs_clarification=True,
              questions=[{"question": "Can a rating be changed later?", "why": "affects the data model"}])


def services_with(replies, **settings):
    root = make_project()
    s = make_settings(**settings)
    return Services(s, backend=FakeBackend(root), llm=MockLLM([json.dumps(r) if isinstance(r, dict) else r for r in replies]),
                    store=RunStore(s.data_dir)), root


class AnalyzeTests(unittest.TestCase):
    def test_llm_result_is_used_and_cleaned(self):
        svc, _ = services_with([GOOD])
        u = analyze(svc, "Add a rating feature to exchanges", None, None)
        self.assertEqual(u.source, "llm")
        self.assertEqual(len(u.acceptance_criteria), 2)
        self.assertIn("backend", u.areas)
        self.assertNotIn("bogus", u.areas)            # invalid areas are dropped
        self.assertFalse(u.needs_clarification)

    def test_bad_model_output_falls_back_to_heuristics(self):
        svc, _ = services_with(["nonsense", "still nonsense"])
        u = analyze(svc, "Add a rating feature", None, None)
        self.assertEqual(u.source, "heuristic")
        self.assertEqual(u.acceptance_criteria, ["Add a rating feature"])
        self.assertIn("No language model", u.assumptions[0])

    def test_llm_disabled(self):
        svc, _ = services_with([], use_llm=False)
        self.assertEqual(analyze(svc, "Add a rating feature", None, None).source, "heuristic")
        self.assertEqual(svc.llm.calls, [])           # the model was never called

    def test_too_many_questions_is_a_validation_problem(self):
        v = make_validator(1)
        problems = v(dict(GOOD, questions=[{"question": "a"}, {"question": "b"}]))
        self.assertTrue(any("at most 1" in p for p in problems))

    def test_answers_are_never_asked_again(self):
        svc, _ = services_with([ASKING])
        u = analyze(svc, "Add a rating", None, {"q1": "No edits"}, [{"id": "q1", "question": "Can it change?", "why": ""}])
        self.assertFalse(u.needs_clarification)
        self.assertEqual(u.questions[0]["id"], "q1")
        self.assertIn("No edits", svc.llm.calls[0][0].content)  # the answer reached the prompt

    def test_roundtrip(self):
        u = Understanding("x y z", ["backend"], ["x"])
        self.assertEqual(Understanding.from_dict(u.as_dict()), u)


class PauseAndResumeTests(unittest.TestCase):
    def wait(self, store, run_id, states):
        for _ in range(200):
            if store.get(run_id)["status"] in states:
                return store.get(run_id)
            time.sleep(0.05)
        self.fail(f"run never reached {states}: {store.get(run_id)['status']}")

    def test_run_pauses_for_questions_then_resumes(self):
        svc, root = services_with([ASKING, GOOD])
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        orc = Orchestrator(svc)
        orc.submit(1, 1, "Add star rating to reviews", None)
        run = self.wait(svc.store, 1, ("NEEDS_INPUT", "FAILED"))
        self.assertEqual(run["status"], "NEEDS_INPUT")
        self.assertEqual(run["understanding"]["questions"][0]["id"], "q1")
        self.assertEqual(run["steps"][-1]["status"], "WAITING")
        self.assertIsNone(run["candidates"])                   # discovery has not started yet
        orc.provide_answers(1, {"q1": "No, ratings are final"})
        run = self.wait(svc.store, 1, ("CONTEXT_READY", "AWAITING_APPROVAL", "FAILED"))
        self.assertNotEqual(run["status"], "FAILED", run["error"])
        self.assertEqual(run["answers"], {"q1": "No, ratings are final"})
        self.assertTrue(run["candidates"])                     # now discovery ran, using the answers

    def test_skipping_questions_continues(self):
        svc, root = services_with([ASKING, GOOD])
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        orc = Orchestrator(svc)
        orc.submit(2, 1, "Add star rating to reviews", None)
        self.wait(svc.store, 2, ("NEEDS_INPUT",))
        orc.provide_answers(2, {})
        run = self.wait(svc.store, 2, ("CONTEXT_READY", "AWAITING_APPROVAL", "FAILED"))
        self.assertNotEqual(run["status"], "FAILED", run["error"])

    def test_answers_rejected_when_not_waiting_or_unknown_id(self):
        svc, root = services_with([GOOD])
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        orc = Orchestrator(svc)
        orc.submit(3, 1, "Add star rating to reviews", None)
        self.wait(svc.store, 3, ("CONTEXT_READY", "AWAITING_APPROVAL", "FAILED"))
        with self.assertRaises(AgentError):
            orc.provide_answers(3, {"q1": "x"})

    def test_cancel_a_waiting_run(self):
        svc, root = services_with([ASKING])
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        orc = Orchestrator(svc)
        orc.submit(4, 1, "Add star rating to reviews", None)
        self.wait(svc.store, 4, ("NEEDS_INPUT",))
        self.assertEqual(orc.cancel(4)["status"], "CANCELLED")


if __name__ == "__main__":
    unittest.main()