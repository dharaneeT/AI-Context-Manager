import shutil
import unittest

from app.backend.client import BackendClient
from app.context.files import is_ignored, list_project_files, map_root, read_file, safe_join
from app.context.redact import redact
from app.core.errors import AgentError, BackendUnavailable, NotFound
from tests.helpers import make_project, make_settings, serve


class RedactTests(unittest.TestCase):
    def test_masks_properties_and_tokens(self):
        text, n = redact("server.port=8080\ndb.password=hunter2hunter2\nkey = 'sk-abcdefghijklmnopqrstuvwxyz'\n")
        self.assertIn("server.port=8080", text)
        self.assertNotIn("hunter2", text)
        self.assertNotIn("sk-abcdef", text)
        self.assertGreaterEqual(n, 2)


class FileTests(unittest.TestCase):
    def setUp(self):
        self.root = make_project()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)

    def test_listing_skips_ignored(self):
        files = list_project_files(self.root)
        self.assertIn("backend/pom.xml", files)
        self.assertNotIn("frontend/node_modules/lib/index.js", files)
        self.assertNotIn(".env", files)
        self.assertNotIn("docs/logo.png", files)

    def test_full_file_read_with_redaction(self):
        c = read_file(self.root, "backend/src/main/resources/application.properties", 10_000)
        self.assertFalse(c.skipped)
        self.assertIn("server.port=8080", c.text)
        self.assertNotIn("hunter2", c.text)
        self.assertEqual(c.redactions, 1)

    def test_skip_reasons(self):
        self.assertEqual(read_file(self.root, ".env", 1000).skipped, "ignored by rules")
        self.assertEqual(read_file(self.root, "nope/missing.java", 1000).skipped, "not found")

    def test_truncates_big_files(self):
        c = read_file(self.root, "backend/src/main/java/com/demo/review/ReviewService.java", 20)
        self.assertTrue(c.truncated)

    def test_path_traversal_is_refused(self):
        with self.assertRaises(AgentError):
            safe_join(self.root, "../../etc/passwd")
        with self.assertRaises(AgentError):
            read_file(self.root, "../outside.txt", 1000)

    def test_is_ignored(self):
        self.assertTrue(is_ignored("a/node_modules/x.js"))
        self.assertTrue(is_ignored("x/Secrets.PEM"))
        self.assertFalse(is_ignored("src/App.jsx"))

    def test_map_root_for_docker_paths(self):
        parent = self.root.parent
        mapped = map_root(f"/workspace/{self.root.name}", (("/workspace", str(parent)),))
        self.assertEqual(mapped, self.root.resolve())
        with self.assertRaises(NotFound):
            map_root("/workspace/does-not-exist", (("/workspace", str(parent)),))


class BackendClientTests(unittest.TestCase):
    def test_calls_and_errors(self):
        calls = []

        def handler(method, path, body):
            calls.append(path)
            if "missing" in path:
                return 404, {"message": "gone"}
            return 200, {"files": []}

        server, url = serve(handler)
        self.addCleanup(server.shutdown)
        client = BackendClient(make_settings(backend_url=url))
        self.assertEqual(client.rank(1, "add rating thing"), {"files": []})
        self.assertIn("task=add+rating+thing", calls[0])
        with self.assertRaises(NotFound):
            client._get("/api/projects/1/missing")

    def test_backend_down(self):
        with self.assertRaises(BackendUnavailable) as ctx:
            BackendClient(make_settings(backend_url="http://127.0.0.1:1")).project(1)
        self.assertIn("Is it running", ctx.exception.message)


if __name__ == "__main__":
    unittest.main()