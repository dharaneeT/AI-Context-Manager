import shutil
import unittest
from pathlib import Path

from app.repair.context import (build_repair_prompt, find_declarations, pick_targets, symbol_from, weakens_test, window)
from app.verify.parsers import failure
from tests.test_day11_sandbox import make_ws, wait_for
from tests.test_day13_implement import CTRL, GOOD_CREATE, STEPS, SVC, blocks, planned_run
from tests.test_day14_verify import python_command
from tests.helpers import make_settings

CHECKER = ("import pathlib, re, sys\n"
           "p = pathlib.Path('src/main/java/com/demo/review/ReviewService.java')\n"
           "text = p.read_text()\n"
           "if 'Math.min' in text:\n    print('BUILD SUCCESS'); sys.exit(0)\n"
           "words = re.findall(r'// (\\w+)', text)\n"
           "print('[ERROR] ' + str(p.resolve()) + ':[4,16] ' + (words[-1] if words else 'none') + ' problem')\n"
           "sys.exit(1)\n")
ALPHA = blocks(SVC, "return stars;", "return stars; // alpha")          # the implementation: compiles badly
FIX = blocks(SVC, "return stars; // alpha", "return Math.min(5, stars);")
BETA = blocks(SVC, "return stars; // alpha", "return stars; // beta")    # a repair that changes the error
SAME = blocks(SVC, "return stars; // alpha", "return stars; // alpha again")


class ContextTests(unittest.TestCase):
    def setUp(self):
        self.root, self.settings, self.meta, self.tree = make_ws({
            "backend/src/main/java/com/demo/review/ReviewRepository.java":
                "package com.demo.review;\n\npublic interface ReviewRepository {\n    int countByStars(int stars);\n}\n",
            "frontend/src/util.js": "export function clamp(a) {\n  return a;\n}\n"})
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.addCleanup(shutil.rmtree, self.settings.data_dir, ignore_errors=True)

    def test_symbol_names(self):
        self.assertEqual(symbol_from("cannot find symbol [symbol: method findByStars(int)] [location: x]"), "findByStars")
        self.assertEqual(symbol_from("cannot find symbol [symbol: class Rating]"), "Rating")
        self.assertEqual(symbol_from("'clamp' is not defined (no-undef)"), "clamp")
        self.assertEqual(symbol_from('Failed to resolve import "./Nope" from "src/App.jsx"'), "Nope")
        self.assertIsNone(symbol_from("expected: <5> but was: <6>"))

    def test_declarations_are_found_in_java_and_js(self):
        method = find_declarations(self.tree, "countByStars")
        self.assertIn("ReviewRepository.java:4", method[0])
        self.assertIn("int countByStars(int stars);", method[0])
        self.assertIn("public class ReviewService", find_declarations(self.tree, "ReviewService")[0])
        self.assertIn("util.js:1", find_declarations(self.tree, "clamp")[0])
        self.assertEqual(find_declarations(self.tree, "doesNotExist"), [])

    def test_window_has_line_numbers(self):
        w = window(self.tree, SVC, 4, 2)
        self.assertIn("4:     public int rate(int stars) {", w)
        self.assertTrue(w.startswith("=== " + SVC))

    def test_tests_may_not_be_weakened(self):
        t = "backend/src/test/java/com/demo/review/ReviewServiceTest.java"
        self.assertTrue(weakens_test(t, "assertEquals(5, x);\nassertTrue(y);", "assertTrue(y);"))
        self.assertTrue(weakens_test(t, "assertEquals(5, x);", "// removed"))
        self.assertFalse(weakens_test(t, "assertEquals(5, x);", "assertEquals(4, x);"))
        self.assertFalse(weakens_test(SVC, "assertEquals(5, x);", ""))   # only test files are protected

    def test_targets_are_distinct_and_ordered(self):
        a, b = failure("test", "x", "T.java", 1, "T.a"), failure("compile", "y", "A.java", 2)
        picked = pick_targets([a, b, dict(b), failure("lint", "z", "B.js", 3)], 3)
        self.assertEqual([f["kind"] for f in picked], ["compile", "test", "lint"])

    def test_prompt_carries_the_declaration_and_fits_a_small_window(self):
        f = failure("compile", "cannot find symbol [symbol: method countByStars(int)]", SVC, 4, command="backend:compile")
        prompt = build_repair_prompt(self.settings, {"command": "x", "understanding": {}}, self.tree, [f], {SVC}, 1, [])
        self.assertIn("int countByStars(int stars);", prompt.text)       # the real declaration is shown
        self.assertIn("4:     public int rate", prompt.text)
        self.assertLess(len(prompt.text) // 4, self.settings.prompt_budget_tokens)


class LoopTests(unittest.TestCase):
    def flow(self, replies, checker=CHECKER, **settings):
        svc, orc, llm, root = planned_run(self, STEPS, replies, **settings)
        (root / "backend" / "checker.py").write_text(checker, encoding="utf-8")
        profile = svc.store.get(1)["profile"]
        profile["commands"]["backend:compile"] = python_command("backend", "checker")
        svc.store.update(1, profile=profile)
        orc.start_implementation(1)
        run = wait_for(svc, ("COMPLETE", "PARTIAL", "FAILED", "VERIFY_FAILED", "VERIFIED"))
        return svc, llm, run

    def test_a_broken_build_is_repaired(self):
        svc, llm, run = self.flow([ALPHA, GOOD_CREATE, FIX])
        self.assertEqual(run["status"], "COMPLETE", run["error"])
        self.assertEqual(len(run["repair"]["attempts"]), 1)
        self.assertEqual(run["repair"]["attempts"][0]["result"], "fixed")
        self.assertEqual([a["ok"] for a in run["verification"]["attempts"]], [False, True])
        self.assertEqual(run["repair"]["attempts"][0]["targets"][0]["message"], "alpha problem")
        self.assertIn("alpha problem", llm.calls[2][0].content)            # the repair prompt carried the real error
        self.assertTrue(run["stage"].startswith("Complete"))
        tree = Path(run["workspace"]["path"])
        self.assertIn("Math.min(5, stars)", (tree / SVC).read_text(encoding="utf-8"))
        self.assertIn("(repair 1)", " ".join(c.get("label", "") + " (repair %s)" % c.get("repair") for c in run["changes"]))

    def test_it_stops_when_the_same_error_comes_back(self):
        svc, llm, run = self.flow([ALPHA, GOOD_CREATE, SAME, FIX])
        self.assertEqual(run["status"], "PARTIAL")
        self.assertIn("same errors came back", run["repair"]["stopped"])
        self.assertEqual(len(run["repair"]["attempts"]), 1)
        self.assertFalse(run["repair"]["ok"])
        self.assertTrue(run["repair"]["diagnostics"])
        self.assertEqual(len(llm.replies), 1)                              # the unused 4th reply: it did not keep guessing

    def test_attempts_are_bounded(self):
        svc, llm, run = self.flow([ALPHA, GOOD_CREATE, BETA, FIX], max_repair_attempts=1)
        self.assertEqual(run["status"], "PARTIAL")
        self.assertIn("1 repair attempt(s) were used", run["repair"]["stopped"])
        self.assertIn("beta problem", run["error"])

    def test_it_never_edits_outside_the_approved_plan(self):
        outside = blocks(CTRL, "ReviewService s;", "ReviewService s2;")
        svc, llm, run = self.flow([ALPHA, GOOD_CREATE, outside])
        self.assertEqual(run["status"], "PARTIAL")
        self.assertEqual(run["repair"]["needs_approval"][0]["path"], CTRL)
        self.assertIn("outside the approved plan", run["repair"]["stopped"])
        tree = Path(run["workspace"]["path"])
        self.assertIn("ReviewService s;", (tree / CTRL).read_text(encoding="utf-8"))   # untouched

    def test_environment_problems_are_not_repaired_by_changing_code(self):
        checker = "import sys\nprint(\"'mvnw.cmd' is not recognized as an internal or external command\")\nsys.exit(1)\n"
        svc, llm, run = self.flow([ALPHA, GOOD_CREATE], checker=checker)
        self.assertEqual(run["status"], "PARTIAL")
        self.assertIn("needs a person", run["repair"]["stopped"])
        self.assertEqual(llm.replies, [])
        self.assertEqual(len(llm.calls), 2)                                  # only the two implementation calls

    def test_a_model_that_cannot_produce_edits_is_given_up_on(self):
        svc, llm, run = self.flow([ALPHA, GOOD_CREATE, "I think the code is fine.", "Really, it is fine."])
        self.assertEqual(run["status"], "PARTIAL")
        self.assertIn("could not produce an acceptable edit", run["repair"]["stopped"])

    def test_nothing_to_repair_when_verification_passes(self):
        ok = blocks(SVC, "return stars;", "return Math.min(5, stars);")
        svc, llm, run = self.flow([ok, GOOD_CREATE])
        self.assertEqual(run["status"], "COMPLETE")
        self.assertIsNone(run.get("repair"))

    def test_partial_runs_can_try_again(self):
        svc, llm, run = self.flow([ALPHA, GOOD_CREATE, "no edits here", "still none"])
        self.assertEqual(run["status"], "PARTIAL")
        llm.replies.append(FIX)
        svc.orchestrator.start_implementation(1)
        run = wait_for(svc, ("COMPLETE", "FAILED"))
        self.assertEqual(run["status"], "COMPLETE", run["error"])


if __name__ == "__main__":
    unittest.main()
