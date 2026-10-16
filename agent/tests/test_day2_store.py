import tempfile
import unittest
from pathlib import Path

from app.context.understand import understand
from app.core.errors import AgentError, NotFound
from app.orchestrator.store import RunStore


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(prefix="cl_store_"))

    def test_create_get_list(self):
        s = RunStore(self.dir)
        s.create(1, 5, "Add a rating", 6000)
        self.assertEqual(s.get(1)["status"], "QUEUED")
        self.assertEqual(s.list()[0]["id"], 1)
        self.assertNotIn("steps", s.list()[0])  # list view stays small

    def test_same_run_cannot_start_twice(self):
        s = RunStore(self.dir)
        s.create(1, 5, "Add a rating", None)
        with self.assertRaises(AgentError) as ctx:
            s.create(1, 5, "Add a rating", None)
        self.assertEqual(ctx.exception.status, 409)

    def test_unknown_run(self):
        with self.assertRaises(NotFound):
            RunStore(self.dir).get(99)

    def test_steps_are_recorded(self):
        s = RunStore(self.dir)
        s.create(1, 5, "x y z", None)
        s.begin_step(1, "Understand")
        s.end_step(1, "DONE", "ok")
        step = s.get(1)["steps"][0]
        self.assertEqual((step["stage"], step["status"], step["summary"]), ("Understand", "DONE", "ok"))

    def test_survives_reload(self):
        RunStore(self.dir).create(4, 1, "persist me", None)
        self.assertEqual(RunStore(self.dir).get(4)["command"], "persist me")  # (marked interrupted, still there)


class UnderstandTests(unittest.TestCase):
    def test_no_areas_is_fine(self):
        self.assertEqual(understand("Rename the thing").areas, [])


if __name__ == "__main__":
    unittest.main()