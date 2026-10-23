import shutil
import sys
import time
import unittest
from pathlib import Path

from app.verify.parsers import (classify_environment, parse_eslint, parse_js_tests, parse_maven_compile,
                                parse_node_build, parse_result, parse_surefire, relativize, signature)
from app.verify.runner import commands_from_plan, describe, failures_of, verify_once
from tests.test_day11_sandbox import make_ws, wait_for
from tests.test_day13_implement import GOOD_CREATE, GOOD_MODIFY, STEPS, SVC, blocks, planned_run

MAVEN_ERRORS = """[INFO] Compiling 3 source files to /x/target/classes
[ERROR] COMPILATION ERROR :
[ERROR] {tree}/backend/src/main/java/com/demo/review/ReviewService.java:[5,20] cannot find symbol
  symbol:   method findByStars(int)
  location: variable repo of type com.demo.ReviewRepository
[ERROR] {tree}/backend/src/main/java/com/demo/review/ReviewController.java:[9,1] class, interface, enum, or record expected
[ERROR] COMPILATION ERROR :
[ERROR] {tree}/backend/src/main/java/com/demo/review/ReviewController.java:[9,1] class, interface, enum, or record expected
"""
SUREFIRE = """<?xml version="1.0"?>
<testsuite name="com.demo.review.ReviewServiceTest" tests="2" failures="1" errors="0" skipped="0">
 <testcase classname="com.demo.review.ReviewServiceTest" name="ratesOne"/>
 <testcase classname="com.demo.review.ReviewServiceTest" name="ratesTooHigh">
  <failure message="expected: &lt;5&gt; but was: &lt;6&gt;" type="org.opentest4j.AssertionFailedError">org.opentest4j.AssertionFailedError: expected
	at com.demo.review.ReviewServiceTest.ratesTooHigh(ReviewServiceTest.java:12)
  </failure></testcase></testsuite>"""
PROFILE = {"modules": [{"label": "backend", "kind": "maven", "dir": "backend"}, {"label": "frontend", "kind": "node", "dir": "frontend"}],
           "commands": {}}


class ParserTests(unittest.TestCase):
    def setUp(self):
        self.root, self.settings, self.meta, self.tree = make_ws()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.addCleanup(shutil.rmtree, self.settings.data_dir, ignore_errors=True)

    def test_maven_compile_errors(self):
        out = MAVEN_ERRORS.format(tree=self.tree)
        fails = parse_maven_compile(out, self.tree, "backend:compile")
        self.assertEqual(len(fails), 2)                                   # duplicates collapsed
        f = fails[0]
        self.assertEqual((f["kind"], f["file"], f["line"]), ("compile", "backend/src/main/java/com/demo/review/ReviewService.java", 5))
        self.assertIn("cannot find symbol", f["message"])
        self.assertIn("method findByStars(int)", f["message"])            # the detail lines are attached
        self.assertIn("location: variable repo", f["message"])
        prefixed = out.replace("\n  symbol:", "\n[ERROR]   symbol:").replace("\n  location:", "\n[ERROR]   location:")
        self.assertIn("method findByStars(int)", parse_maven_compile(prefixed, self.tree)[0]["message"])  # Maven's own format

    def test_signature_ignores_line_numbers_and_folders(self):
        a = signature("compile", "a/Foo.java", "cannot find symbol [symbol: method f(int)] at line 12")
        b = signature("compile", "other/dir/Foo.java", "cannot find symbol [symbol: method f(int)] at line 99")
        c = signature("compile", "a/Foo.java", "cannot find symbol [symbol: method g(int)]")
        self.assertEqual(a, b)
        self.assertNotEqual(a, c)

    def test_surefire_xml(self):
        report = self.tree / "backend/target/surefire-reports"
        report.mkdir(parents=True)
        (report / "TEST-com.demo.review.ReviewServiceTest.xml").write_text(SUREFIRE, encoding="utf-8")
        fails, summary = parse_surefire(self.tree, "backend", time.time() - 100, "backend:test")
        self.assertEqual({k: summary[k] for k in ("tests", "failures")}, {"tests": 2, "failures": 1})
        self.assertEqual(len(fails), 1)
        self.assertEqual((fails[0]["test"], fails[0]["line"]), ("ReviewServiceTest.ratesTooHigh", 12))
        self.assertEqual(fails[0]["file"], "backend/src/test/java/com/demo/review/ReviewServiceTest.java")
        self.assertIn("expected: <5> but was: <6>", fails[0]["message"])

    def test_stale_reports_are_ignored(self):
        report = self.tree / "backend/target/surefire-reports"
        report.mkdir(parents=True)
        (report / "TEST-x.xml").write_text(SUREFIRE, encoding="utf-8")
        fails, summary = parse_surefire(self.tree, "backend", time.time() + 100)  # "this run started in the future"
        self.assertEqual((fails, summary["tests"]), ([], 0))

    def test_node_build_lint_and_tests(self):
        t = self.tree
        esb = f'{t}/frontend/src/components/A.jsx:12:4: ERROR: Unexpected "}}"\n'
        self.assertEqual(parse_node_build(esb, t, "frontend")[0]["line"], 12)
        roll = "src/components/B.jsx (3:9): Unexpected token (Note that you need plugins)\n"
        self.assertEqual(parse_node_build(roll, t, "frontend")[0]["file"], "src/components/B.jsx")
        res = 'Failed to resolve import "./Nope" from "src/App.jsx". Does the file exist?'
        self.assertIn("./Nope", parse_node_build(res, t, "frontend")[0]["message"])
        lint = f"{t}/frontend/src/components/A.jsx\n  12:5  error  'x' is not defined  no-undef\n  14:1  warning  unused var  no-unused-vars\n"
        found = parse_eslint(lint, t)
        self.assertEqual([(f["line"], f["kind"]) for f in found], [(12, "lint")])
        self.assertIn("no-undef", found[0]["message"])
        tests = " FAIL  src/a.test.jsx > renders\nAssertionError: expected 1 to be 2\n"
        self.assertEqual(parse_js_tests(tests, t)[0]["message"], "AssertionError: expected 1 to be 2")

    def test_environment_problems(self):
        self.assertIn("missing", classify_environment("'mvnw.cmd' is not recognized as an internal or external command"))
        self.assertIn("Java", classify_environment("Error: JAVA_HOME is not defined correctly"))
        self.assertIn("offline", classify_environment("Could not transfer artifact org.x:y:pom:1 from central"))
        self.assertIn("node_modules", classify_environment("Error: Cannot find module 'vite'"))
        self.assertIsNone(classify_environment("Cannot find module './local'"))

    def test_relativize(self):
        self.assertEqual(relativize(f"{self.tree}/backend/A.java", self.tree), "backend/A.java")
        self.assertEqual(relativize("C:\\other\\tree\\backend\\src\\A.java", self.tree), "backend/src/A.java")

    def test_unreadable_failure_is_still_a_failure(self):
        profile = {**PROFILE, "commands": {"backend:compile": {"cwd": "backend", "argv": ["mvn"], "available": True}}}
        res = {"command": "backend:compile", "output": "something odd happened\nbye", "exit_code": 2,
               "timed_out": False, "environment_problem": False}
        fails, _, _ = parse_result(res, profile, self.tree, time.time())
        self.assertEqual((len(fails), fails[0]["kind"]), (1, "compile"))
        self.assertIn("exit code 2", fails[0]["message"])

    def test_timeout_and_missing_program(self):
        base = {"command": "backend:test", "output": "", "exit_code": -9, "timed_out": True, "environment_problem": False}
        self.assertEqual(parse_result(base, PROFILE, self.tree, 0)[0][0]["kind"], "timeout")
        base.update(timed_out=False, exit_code=-1, environment_problem=True)
        self.assertEqual(parse_result(base, PROFILE, self.tree, 0)[0][0]["kind"], "environment")


class Ctx:
    def __init__(self, settings):
        self.settings = settings

    def check_cancel(self):
        pass


def python_command(cwd, module):
    return {"cwd": cwd, "argv": [sys.executable, "-m", module], "available": True, "reason": ""}


class VerifyOnceTests(unittest.TestCase):
    def setUp(self):
        self.root, self.settings, self.meta, self.tree = make_ws()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.addCleanup(shutil.rmtree, self.settings.data_dir, ignore_errors=True)
        self.ctx = Ctx(self.settings)

    def script(self, name, body):
        (self.tree / "backend" / f"{name}.py").write_text(body, encoding="utf-8")

    def profile(self, **cmds):
        return {"modules": PROFILE["modules"], "commands": {k: python_command("backend", v) for k, v in cmds.items()}}

    def test_pass_is_evidence_based(self):
        self.script("ok", "print('BUILD SUCCESS')")
        a = verify_once(self.ctx, self.tree, self.profile(**{"backend:compile": "ok"}), ["backend:compile"], 1)
        self.assertTrue(a["ok"])
        self.assertEqual(a["results"][0]["exit_code"], 0)

    def test_a_failure_is_parsed_and_later_checks_are_skipped(self):
        self.script("bad", f"import sys\nprint('[ERROR] {self.tree.as_posix()}/backend/src/main/java/com/demo/review/ReviewService.java:[5,20] cannot find symbol')\nsys.exit(1)")
        self.script("ok", "print('fine')")
        a = verify_once(self.ctx, self.tree, self.profile(**{"backend:compile": "bad", "backend:test": "ok"}),
                        ["backend:compile", "backend:test"], 1)
        self.assertFalse(a["ok"])
        self.assertEqual(a["results"][0]["failures"][0]["line"], 5)
        self.assertTrue(a["results"][1]["skipped"])
        self.assertIn("does not build", a["results"][1]["reason"])
        self.assertEqual(len(failures_of(a)), 1)
        self.assertIn("cannot find symbol", describe(a))

    def test_zero_tests_is_a_warning_not_a_proof(self):
        self.script("empty", "import pathlib\np = pathlib.Path('target/surefire-reports'); p.mkdir(parents=True, exist_ok=True)\n"
                             "(p / 'TEST-e.xml').write_text('<testsuite tests=\"0\" failures=\"0\" errors=\"0\" skipped=\"0\"/>')")
        a = verify_once(self.ctx, self.tree, self.profile(**{"backend:test": "empty"}), ["backend:test"], 1)
        self.assertTrue(a["ok"])
        self.assertIn("no tests were executed", a["results"][0]["warnings"][0])

    def test_refused_and_unavailable_commands_are_reported(self):
        p = self.profile(**{"backend:compile": "ok"})
        p["commands"]["backend:test"] = {"cwd": "backend", "argv": ["mvn"], "available": False, "reason": "no test dependency"}
        a = verify_once(self.ctx, self.tree, p, ["backend:test"], 1)
        self.assertFalse(a["ok"])
        self.assertEqual(a["results"][0]["failures"][0]["kind"], "environment")
        self.assertIn("not available", a["results"][0]["failures"][0]["message"])

    def test_commands_come_from_the_plan_in_order_without_duplicates(self):
        plan = {"steps": [{"action": "modify", "files": ["a"]}, {"action": "verify", "verify": "backend:compile", "files": []},
                          {"action": "verify", "verify": "backend:test", "files": []}, {"action": "verify", "verify": "backend:compile", "files": []}]}
        self.assertEqual(commands_from_plan(plan), ["backend:compile", "backend:test"])


class StageTests(unittest.TestCase):
    CHECKER = ("import pathlib, sys\n"
               "text = pathlib.Path('src/main/java/com/demo/review/ReviewService.java').read_text()\n"
               "if 'Math.min' in text: print('BUILD SUCCESS'); sys.exit(0)\n"
               "print('[ERROR] ' + str(pathlib.Path('src/main/java/com/demo/review/ReviewService.java').resolve()) + ':[4,16] incompatible types')\n"
               "sys.exit(1)\n")

    def run_flow(self, replies):
        svc, orc, llm, root = planned_run(self, STEPS, replies)
        (root / "backend" / "checker.py").write_text(self.CHECKER, encoding="utf-8")
        profile = svc.store.get(1)["profile"]
        profile["commands"]["backend:compile"] = python_command("backend", "checker")
        svc.store.update(1, profile=profile)
        orc.start_implementation(1)
        return wait_for(svc, ("VERIFIED", "VERIFY_FAILED", "PARTIAL", "FAILED", "COMPLETE", "IMPLEMENTED"))

    def test_verified_when_the_build_passes(self):
        run = self.run_flow([GOOD_MODIFY, GOOD_CREATE])
        self.assertIn(run["status"], ("VERIFIED", "COMPLETE"), run["error"])
        att = run["verification"]["attempts"][0]
        self.assertTrue(att["ok"])
        self.assertEqual(att["results"][0]["command"], "backend:compile")

    def test_verification_failure_is_recorded_with_the_error(self):
        harmless = blocks(SVC, "return stars;", "return stars; // no clamp")
        run = self.run_flow([harmless, GOOD_CREATE])
        self.assertIn(run["status"], ("VERIFY_FAILED", "PARTIAL"))
        att = run["verification"]["attempts"][0]
        self.assertFalse(att["ok"])
        f = att["results"][0]["failures"][0]
        self.assertEqual((f["kind"], f["file"], f["line"]), ("compile", "backend/src/main/java/com/demo/review/ReviewService.java", 4))
        self.assertIn("incompatible types", run["error"])


if __name__ == "__main__":
    unittest.main()