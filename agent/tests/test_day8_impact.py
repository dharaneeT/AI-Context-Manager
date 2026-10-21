import shutil
import time
import unittest

from app.deps import Services
from app.orchestrator.runner import Orchestrator
from app.orchestrator.store import RunStore
from app.planner.impact import classify, compute_impact, concepts_of
from tests.helpers import FakeBackend, make_project, make_settings

PROFILE = {"modules": [{"label": "backend", "frameworks": ["Spring Boot"]}],
           "java": {"module_dir": "backend", "base_package": "com.demo", "base_dir": "com/demo", "layout": "by-feature",
                    "test_suffix": "Test"},
           "frontend": {"component_dir": "frontend/src/components", "component_ext": "jsx"}}


def cand(path, score, *reasons):
    return {"path": path, "score": score, "reasons": list(reasons) or ["match"], "sources": [], "ranges": []}


class ClassifyTests(unittest.TestCase):
    def test_by_name_annotation_and_extension(self):
        self.assertEqual(classify("a/TaskController.java"), "controller")
        self.assertEqual(classify("a/TaskRepository.java"), "repository")
        self.assertEqual(classify("a/Review.java", "@Entity @Table(name = \"r\")"), "entity")
        self.assertEqual(classify("a/Thing.java", "@Service"), "service")
        self.assertEqual(classify("a/TaskServiceTest.java"), "test")
        self.assertEqual(classify("backend/pom.xml"), "config")
        self.assertEqual(classify("frontend/src/api.js"), "api-client")
        self.assertEqual(classify("frontend/src/components/A.jsx"), "ui-component")
        self.assertEqual(classify("db/V1__init.sql"), "migration")


class ConceptTests(unittest.TestCase):
    def test_concepts(self):
        self.assertEqual(concepts_of("Add a rating feature to exchanges", ["rating", "exchanges"])[0], "rating")
        self.assertEqual(concepts_of("Add pagination to the list", ["pagination", "list"]), [])  # cross-cutting, not an entity


class ImpactTests(unittest.TestCase):
    def base(self, command, cands, annotations=None, dependents=None, files=None, **u):
        understanding = {"areas": u.get("areas", []), "keywords": u.get("keywords", []),
                         "acceptance_criteria": u.get("criteria", []), "constraints": []}
        return compute_impact(command, understanding, PROFILE, cands, annotations or {}, dependents or {}, files or [])

    def test_new_concept_gets_files_in_the_projects_layout(self):
        r = self.base("Add a rating feature to exchanges", [cand("backend/src/main/java/com/demo/exchange/ExchangeService.java", 0.9)],
                      keywords=["rating"], areas=["backend", "database", "frontend"])
        self.assertEqual(r["decisions"][0]["decision"], "create")
        paths = [f["path"] for f in r["suggested_new_files"]]
        self.assertIn("backend/src/main/java/com/demo/rating/Rating.java", paths)
        self.assertIn("backend/src/main/java/com/demo/rating/RatingService.java", paths)
        self.assertIn("backend/src/test/java/com/demo/rating/RatingServiceTest.java", paths)
        self.assertIn("frontend/src/components/RatingPanel.jsx", paths)
        self.assertTrue(r["flags"]["database"])

    def test_existing_entity_is_extended_not_duplicated(self):
        r = self.base("Add a rating to every review", [cand("backend/src/main/java/com/demo/review/Review.java", 0.9)],
                      annotations={"backend/src/main/java/com/demo/review/Review.java": "@Entity"}, keywords=["review"])
        by_concept = {d["concept"]: d for d in r["decisions"]}
        self.assertEqual(by_concept["review"]["decision"], "extend")      # Review already exists: never duplicated
        self.assertEqual(by_concept["review"]["target"], "backend/src/main/java/com/demo/review/Review.java")
        self.assertEqual(by_concept["rating"]["decision"], "create")      # Rating is the new concept
        self.assertFalse(any(f["path"].endswith("/Review.java") for f in r["suggested_new_files"]))

    def test_roles_follow_score_and_type(self):
        r = self.base("Change the task list", [
            cand("a/TaskService.java", 0.9), cand("a/TaskRepository.java", 0.3), cand("a/TaskServiceTest.java", 0.5, "test for a/TaskService.java"),
            cand("backend/pom.xml", 0.08, "build/config file")])
        role = {c["path"]: c["role"] for c in r["components"]}
        self.assertEqual(role["a/TaskService.java"], "modify")
        self.assertEqual(role["a/TaskRepository.java"], "reference")
        self.assertEqual(role["a/TaskServiceTest.java"], "test")
        self.assertEqual(role["backend/pom.xml"], "reference")      # config only when the task is about config
        self.assertEqual(r["risks"], [])                              # the modified file has a test

    def test_risks(self):
        r = self.base("Change the task list", [cand("a/TaskService.java", 0.9)], dependents={"a/TaskService.java": 12})
        text = " ".join(r["risks"])
        self.assertIn("used by 12 files", text)
        self.assertIn("No existing test found for a/TaskService.java", text)

    def test_flags_from_the_task_text(self):
        r = self.base("Only participants may rate, from 1 to 5", [cand("a/X.java", 0.9)], criteria=["A rating must be between 1 and 5"])
        self.assertTrue(r["flags"]["authorization"])
        self.assertTrue(r["flags"]["validation"])
        self.assertIn("no security framework", " ".join(r["risks"]))


class StageTests(unittest.TestCase):
    def test_stage_runs_in_the_pipeline(self):
        root = make_project()
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        s = make_settings(use_llm=False)
        svc = Services(s, backend=FakeBackend(root), store=RunStore(s.data_dir))
        Orchestrator(svc).submit(1, 1, "Add star rating to reviews", None)
        for _ in range(200):
            run = svc.store.get(1)
            if run["status"] not in ("QUEUED", "RUNNING"):
                break
            time.sleep(0.05)
        self.assertIsNotNone(run["impact"], run["error"])
        layers = {c["path"].rsplit("/", 1)[-1]: c["layer"] for c in run["impact"]["components"]}
        self.assertEqual(layers["ReviewService.java"], "service")
        self.assertEqual(layers["ReviewController.java"], "controller")
        self.assertIn("Impact", [x["stage"] for x in run["steps"]])


if __name__ == "__main__":
    unittest.main()