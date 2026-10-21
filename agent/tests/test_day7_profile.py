import json
import shutil
import tempfile
import unittest
from pathlib import Path

from app.context.files import list_project_files
from app.context.profile import available_commands, conventions_text, detect_profile, get_profile, parse_pom
from app.deps import Services
from app.orchestrator.store import RunStore
from tests.helpers import FakeBackend, make_settings

POM = """<?xml version="1.0"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId></parent>
  <properties><java.version>21</java.version></properties>
  <dependencies>
    <dependency><artifactId>spring-boot-starter-webmvc</artifactId></dependency>
    <dependency><artifactId>lombok</artifactId></dependency>
    <dependency><artifactId>spring-boot-starter-webmvc-test</artifactId><scope>test</scope></dependency>
  </dependencies>
</project>"""
PKG = json.dumps({"scripts": {"dev": "vite", "build": "vite build", "lint": "eslint ."},
                  "dependencies": {"react": "1"}, "devDependencies": {"vite": "1"}})
JAVA = "package com.demo.{pkg};\n\npublic class {name} {{\n\tint x;\n}}\n"


def make_demo() -> Path:
    root = Path(tempfile.mkdtemp(prefix="cl_profile_"))
    files = {
        "backend/pom.xml": POM, "backend/mvnw": "#!/bin/sh", "frontend/package.json": PKG,
        "frontend/eslint.config.js": "export default []",
        "frontend/src/components/Panel.jsx": "export default function Panel() {\n  return null;\n}\n",
        "frontend/src/components/Card.jsx": "export default function Card() {\n  return null;\n}\n",
    }
    for pkg, names in {"task": ["TaskController", "TaskService", "TaskRepository", "TaskDto"],
                       "project": ["ProjectController", "ProjectService"]}.items():
        for n in names:
            files[f"backend/src/main/java/com/demo/{pkg}/{n}.java"] = JAVA.format(pkg=pkg, name=n)
    files["backend/src/test/java/com/demo/task/TaskServiceTest.java"] = JAVA.format(pkg="task", name="TaskServiceTest")
    for rel, text in files.items():
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
    return root


class ProfileTests(unittest.TestCase):
    def setUp(self):
        self.root = make_demo()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.profile = detect_profile(self.root, list_project_files(self.root))

    def test_pom_parsing_is_namespace_safe(self):
        info = parse_pom(self.root / "backend/pom.xml")
        self.assertEqual(info["parent"], "spring-boot-starter-parent")
        self.assertEqual(info["java"], "21")
        self.assertIn("lombok", info["dependencies"])

    def test_modules_and_frameworks(self):
        labels = {m["label"]: m for m in self.profile["modules"]}
        self.assertEqual(set(labels), {"backend", "frontend"})
        self.assertIn("Spring Boot", labels["backend"]["frameworks"])
        self.assertIn("React", labels["frontend"]["frameworks"])

    def test_commands_and_why_unavailable(self):
        cmds = self.profile["commands"]
        self.assertTrue(cmds["backend:compile"]["available"])
        self.assertTrue(cmds["backend:test"]["available"])
        self.assertTrue(cmds["frontend:build"]["available"])
        self.assertFalse(cmds["frontend:test"]["available"])
        self.assertIn("no 'test' script", cmds["frontend:test"]["reason"])
        self.assertEqual(cmds["backend:compile"]["cwd"], "backend")
        self.assertEqual(available_commands(self.profile), ["backend:compile", "backend:test", "frontend:build", "frontend:lint"])

    def test_java_layout_and_naming(self):
        j = self.profile["java"]
        self.assertEqual(j["base_package"], "com.demo")
        self.assertEqual(j["layout"], "by-feature")
        self.assertEqual(j["module_dir"], "backend")
        self.assertEqual(j["class_suffixes"]["Controller"], 2)
        self.assertEqual(j["test_suffix"], "Test")

    def test_indentation_and_frontend(self):
        self.assertEqual(self.profile["indent"]["java"], "tabs")
        self.assertEqual(self.profile["indent"]["javascript"], "2 spaces")
        self.assertEqual(self.profile["frontend"]["component_dir"], "frontend/src/components")
        self.assertIn("frontend/eslint.config.js", self.profile["style_files"])

    def test_conventions_text_is_small(self):
        text = conventions_text(self.profile, 900)
        self.assertIn("com.demo", text)
        self.assertIn("backend:compile", text)
        self.assertLessEqual(len(text), 900)


class CacheTests(unittest.TestCase):
    def test_cached_until_a_build_file_changes(self):
        root = make_demo()
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        s = make_settings()
        svc = Services(s, backend=FakeBackend(root), store=RunStore(s.data_dir))
        self.assertFalse(get_profile(svc, 1)["cached"])
        self.assertTrue(get_profile(svc, 1)["cached"])
        (root / "backend/pom.xml").write_text(POM + "\n<!-- changed -->", encoding="utf-8")
        self.assertFalse(get_profile(svc, 1)["cached"])
        self.assertFalse(get_profile(svc, 1, force=True)["cached"])


if __name__ == "__main__":
    unittest.main()