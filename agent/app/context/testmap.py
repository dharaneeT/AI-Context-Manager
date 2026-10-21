"""Find tests and config files related to a source file, using naming conventions."""
from __future__ import annotations

import re

_CONFIG_NAMES = ("pom.xml", "build.gradle", "package.json", "application.properties", "application.yml",
                 "docker-compose.yml", "vite.config.js", "requirements.txt", "pyproject.toml")


def _stem(path: str) -> str:
    name = path.rsplit("/", 1)[-1]
    return name.rsplit(".", 1)[0] if "." in name else name


def is_test_path(path: str) -> bool:
    low = path.lower()
    stem = _stem(path)
    in_test_dir = "/test/" in low or "/tests/" in low or "__tests__" in low or low.startswith(("test/", "tests/"))
    named_test = (stem.endswith(("Test", "Tests", "IT")) or stem.lower().startswith("test_")
                  or stem.lower().endswith(("_test", ".test", ".spec")))
    return in_test_dir or named_test


def build_test_index(all_files: list) -> dict:
    """lowercase stem -> [paths] for every file that looks like a test."""
    index: dict = {}
    for path in all_files:
        if is_test_path(path):
            index.setdefault(_stem(path).lower(), []).append(path)
    return index


def tests_for(path: str, test_index: dict) -> list:
    """Tests that probably cover `path`: FooTest/FooTests/FooIT, Foo.test, Foo.spec, test_foo, foo_test."""
    if is_test_path(path):
        return []
    s = _stem(path).lower()
    wanted = [s + "test", s + "tests", s + "it", s + "integrationtest", s + ".test", s + ".spec",
              "test_" + s, s + "_test"]
    hits = []
    for key in wanted:
        hits.extend(test_index.get(key, []))
    return list(dict.fromkeys(hits))


def config_files(all_files: list) -> list:
    """Build and configuration files at the project root or in a module's resources."""
    out = []
    for path in all_files:
        name = path.rsplit("/", 1)[-1]
        depth = path.count("/")
        if name in _CONFIG_NAMES and (depth <= 1 or path.endswith("src/main/resources/" + name)):
            out.append(path)
    return out


CONFIG_TASK_WORDS = re.compile(r"\b(config|setting|propert|dependenc|build|docker|database|migration|port|env|library)\w*", re.I)