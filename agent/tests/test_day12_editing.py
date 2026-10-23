import shutil
import unittest

from app.editing.engine import EditError, apply_edits, apply_step, delete_files, find_and_replace
from app.editing.parser import Edit, parse_edits
from app.editing.scope import approved_files
from app.editing.syntax import check_syntax
from app.sandbox import workspace as ws
from tests.test_day11_sandbox import make_ws

SVC = "backend/src/main/java/com/demo/review/ReviewService.java"
CTRL = "backend/src/main/java/com/demo/review/ReviewController.java"
NEW = "backend/src/main/java/com/demo/review/Rating.java"


def block(path, search, replace):
    return f"FILE: {path}\n<<<<<<< SEARCH\n{search}\n=======\n{replace}\n>>>>>>> REPLACE\n"


class ParserTests(unittest.TestCase):
    def test_reads_blocks_and_ignores_chatter_and_fences(self):
        text = "Sure! Here is the change.\n```\n" + block(SVC, "return stars;", "return Math.min(5, stars);") + "```\nDone."
        edits, problems = parse_edits(text)
        self.assertEqual(problems, [])
        self.assertEqual((edits[0].path, edits[0].search, edits[0].replace, edits[0].kind),
                         (SVC, "return stars;", "return Math.min(5, stars);", "replace"))

    def test_several_files_and_blocks_and_create(self):
        text = block(SVC, "a", "b") + block(SVC, "c", "d") + block(NEW, "", "public class Rating {}")
        edits, problems = parse_edits(text)
        self.assertEqual([(e.path, e.kind) for e in edits], [(SVC, "replace"), (SVC, "replace"), (NEW, "create")])

    def test_decorated_file_line(self):
        edits, _ = parse_edits(f"**File: `{SVC}`**\n<<<<<<< SEARCH\nx\n=======\ny\n>>>>>>> REPLACE")
        self.assertEqual(edits[0].path, SVC)

    def test_problems_are_reported(self):
        self.assertIn("no edit blocks", parse_edits("just words")[1][0])
        self.assertIn("before any", parse_edits("<<<<<<< SEARCH\na\n=======\nb\n>>>>>>> REPLACE")[1][0])
        self.assertIn("not finished", parse_edits(f"FILE: {SVC}\n<<<<<<< SEARCH\na\n=======\nb")[1][0])


class MatchTests(unittest.TestCase):
    TEXT = "class A {\n    int f() {\n        return 1;\n    }\n    int g() {\n        return 1;\n    }\n}\n"

    def test_exact(self):
        self.assertIn("return 2;", find_and_replace(self.TEXT, "int f() {\n        return 1;", "int f() {\n        return 2;"))

    def test_wrong_indentation_is_tolerated_and_fixed(self):
        out = find_and_replace(self.TEXT, "int f() {\nreturn 1;\n}", "int f() {\n    return 2;\n}")
        self.assertIn("    int f() {\n        return 2;\n    }", out)

    def test_ambiguous_and_missing(self):
        with self.assertRaises(ValueError) as c:
            find_and_replace(self.TEXT, "return 1;", "return 2;")
        self.assertIn("2 places", str(c.exception))
        with self.assertRaises(ValueError) as c:
            find_and_replace(self.TEXT, "int h() {", "x")
        self.assertIn("not found", str(c.exception))
        self.assertIn("closest line", str(c.exception))   # the model is told what to copy

    def test_empty_replace_deletes_lines(self):
        out = find_and_replace("a\nb\nc\n", "b\n", "")
        self.assertEqual(out, "a\nc\n")


class SyntaxTests(unittest.TestCase):
    def test_java(self):
        ok = 'package a;\npublic class Foo {\n  String s = "}{";\n  char c = \'}\';\n  // }\n  /* { */\n  void f() { }\n}\n'
        self.assertEqual(check_syntax("Foo.java", ok)["problems"], [])
        self.assertTrue(check_syntax("Foo.java", ok.rstrip().rstrip("}"))["hard"])
        self.assertIn("does not match", check_syntax("Foo.java", "class A { ( }")["problems"][0])
        self.assertIn("named Bar.java", check_syntax("Bar.java", "public class Foo {}")["problems"][0])
        self.assertEqual(check_syntax("T.java", 'class T { String t = """\n }}} \n"""; }')["problems"], [])

    def test_other_languages(self):
        self.assertTrue(check_syntax("x.json", "{bad")["hard"])
        self.assertFalse(check_syntax("x.json", '{"a": 1}')["hard"])
        self.assertTrue(check_syntax("x.py", "def f(:\n")["hard"])
        self.assertTrue(check_syntax("pom.xml", "<a><b></a>")["hard"])
        self.assertTrue(check_syntax("api.js", "function f() {")["hard"])

    def test_jsx_is_only_a_warning(self):
        r = check_syntax("A.jsx", "const a = <p>Don't</p>;\nfunction f() {")
        self.assertTrue(r["problems"])
        self.assertFalse(r["hard"])


class EngineTests(unittest.TestCase):
    def setUp(self):
        self.root, self.settings, self.meta, self.tree = make_ws()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.addCleanup(shutil.rmtree, self.settings.data_dir, ignore_errors=True)
        self.allowed = {SVC, CTRL, NEW}
        self.max = 300_000

    def read(self, path):
        return (self.tree / path).read_text(encoding="utf-8")

    def test_modify_and_create_with_line_counts(self):
        edits, _ = parse_edits(block(SVC, "return stars;", "if (stars < 1) {\n            return 1;\n        }\n        return stars;")
                               + block(NEW, "", "package com.demo.review;\n\npublic class Rating {\n}"))
        reports = apply_edits(self.tree, edits, self.allowed, self.max)
        by = {r["path"]: r for r in reports}
        self.assertEqual((by[SVC]["kind"], by[SVC]["added"], by[SVC]["removed"]), ("modify", 3, 0))  # the original return line is kept, so only 3 lines are new
        self.assertEqual(by[NEW]["kind"], "create")
        self.assertIn("class Rating", self.read(NEW))

    def test_all_or_nothing(self):
        edits, _ = parse_edits(block(SVC, "return stars;", "return 5;") + block(CTRL, "THIS IS NOT THERE", "x"))
        before = self.read(SVC)
        with self.assertRaises(EditError) as c:
            apply_edits(self.tree, edits, self.allowed, self.max)
        self.assertEqual(self.read(SVC), before)                        # the good edit was NOT applied either
        self.assertIn("ReviewController.java: the SEARCH text was not found", c.exception.problems[0])

    def test_scope_and_protected_paths(self):
        for path, expect in ((("backend/pom.xml"), "not part of the approved plan"), (".env", "protected"),
                             ("../outside.txt", "relative path"), ("/etc/passwd", "relative path"),
                             (".git/config", "protected"), ("C:/x.txt", "relative path")):
            with self.assertRaises(EditError) as c:
                apply_edits(self.tree, [Edit(path, "", "x")], self.allowed, self.max)
            self.assertIn(expect, c.exception.problems[0], path)

    def test_create_over_existing_and_modify_missing(self):
        with self.assertRaises(EditError) as c:
            apply_edits(self.tree, [Edit(SVC, "", "class X {}")], self.allowed, self.max)
        self.assertIn("already exists", c.exception.problems[0])
        with self.assertRaises(EditError) as c:
            apply_edits(self.tree, [Edit(NEW, "x", "y")], self.allowed, self.max)
        self.assertIn("does not exist", c.exception.problems[0])

    def test_a_syntax_error_is_rolled_back_before_it_is_written(self):
        before = self.read(SVC)
        with self.assertRaises(EditError) as c:
            apply_edits(self.tree, [Edit(SVC, "return stars;", "return (stars;")], self.allowed, self.max)
        self.assertIn("syntax error", c.exception.problems[0])
        self.assertEqual(self.read(SVC), before)

    def test_crlf_files_keep_their_line_endings(self):
        raw = (self.tree / SVC).read_bytes().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
        (self.tree / SVC).write_bytes(raw)
        apply_edits(self.tree, [Edit(SVC, "return stars;", "return 5;")], self.allowed, self.max)
        data = (self.tree / SVC).read_bytes()
        self.assertIn(b"return 5;\r\n", data)
        self.assertNotIn(b"\r\r", data)
        self.assertEqual(data.count(b"\n"), data.count(b"\r\n"))

    def test_size_limit(self):
        with self.assertRaises(EditError) as c:
            apply_edits(self.tree, [Edit(NEW, "", "x" * 500)], self.allowed, 100)
        self.assertIn("beyond", c.exception.problems[0])

    def test_delete_only_approved_existing_files(self):
        with self.assertRaises(EditError):
            delete_files(self.tree, ["backend/pom.xml"], self.allowed)
        with self.assertRaises(EditError):
            delete_files(self.tree, [CTRL.replace("Controller", "Missing")], {CTRL.replace("Controller", "Missing")})
        out = delete_files(self.tree, [CTRL], self.allowed)
        self.assertEqual(out[0]["kind"], "delete")
        self.assertFalse((self.tree / CTRL).exists())

    def test_checkpoints_and_rollback(self):
        base = self.meta["baseline"]
        s1 = apply_step(self.tree, [Edit(SVC, "return stars;", "return 1;")], self.allowed, self.max, "step 1")
        s2 = apply_step(self.tree, [Edit(NEW, "", "class Rating {}")], self.allowed, self.max, "step 2")
        self.assertEqual({c["path"] for c in ws.changed_files(self.tree, base)}, {SVC, NEW})  # vs the baseline, not HEAD
        self.assertIn("return 1;", ws.diff_text(self.tree, base))
        ws.rollback_to(self.tree, s1["checkpoint"])
        self.assertFalse((self.tree / NEW).exists())
        self.assertIn("return 1;", self.read(SVC))
        ws.rollback_to(self.tree, base)
        self.assertEqual(ws.changed_files(self.tree, base), [])

    def test_approved_files_comes_from_the_plan(self):
        run = {"plan": {"steps": [{"action": "modify", "files": [SVC]}, {"action": "create", "files": [NEW]},
                                  {"action": "verify", "files": []}, {"action": "delete", "files": [CTRL]}]}}
        self.assertEqual(approved_files(run), {SVC, NEW})
        self.assertEqual(approved_files(run, ("delete",)), {CTRL})


if __name__ == "__main__":
    unittest.main()