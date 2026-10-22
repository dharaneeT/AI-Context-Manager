import shutil
import tempfile
import unittest
from pathlib import Path
from app.sandbox import workspace as ws
from tests.helpers import make_settings


class WrapperCopyTests(unittest.TestCase):
    def test_wrapper_and_mvn_folder_are_copied_into_the_workspace(self):
        src = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, src, ignore_errors=True)
        (src / "BackEnd" / ".mvn" / "wrapper").mkdir(parents=True)
        (src / "BackEnd" / "pom.xml").write_text("<project/>")
        (src / "BackEnd" / "mvnw.cmd").write_text("@echo off")
        (src / "BackEnd" / "mvnw").write_text("#!/bin/sh")
        (src / "BackEnd" / ".mvn" / "wrapper" / "maven-wrapper.properties").write_text("distributionUrl=x")
        (src / "BackEnd" / "target").mkdir()
        (src / "BackEnd" / "target" / "mvnw.cmd").write_text("must not be copied")
        settings = make_settings()
        meta = ws.create_workspace(settings, 901, src, ["BackEnd/pom.xml"], {"base_commit": None})
        self.addCleanup(ws.destroy_workspace, settings, 901, [])
        tree = Path(meta["path"])
        self.assertTrue((tree / "BackEnd" / "mvnw.cmd").is_file())
        self.assertTrue((tree / "BackEnd" / "mvnw").is_file())
        self.assertTrue((tree / "BackEnd" / ".mvn" / "wrapper" / "maven-wrapper.properties").is_file())
        self.assertFalse((tree / "BackEnd" / "target" / "mvnw.cmd").exists())
        self.assertEqual(ws.changed_files(tree, meta["baseline"]), [])  # the wrapper is part of the baseline




class RecreateWorkspaceTests(unittest.TestCase):
    def test_recreating_a_workspace_replaces_it_and_never_touches_linked_folders(self):
        import os
        src = Path(tempfile.mkdtemp())
        outside = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, src, ignore_errors=True)
        self.addCleanup(shutil.rmtree, outside, ignore_errors=True)
        (src / "a.txt").write_text("hello")
        (outside / "keep.txt").write_text("must survive")
        settings = make_settings()
        first = ws.create_workspace(settings, 902, src, ["a.txt"], {"base_commit": None})
        self.addCleanup(ws.destroy_workspace, settings, 902, [])
        link = Path(first["path"]) / "node_modules"
        try:
            os.symlink(outside, link, target_is_directory=True)
        except OSError:
            self.skipTest("symlinks are not available here")
        (Path(first["path"]) / "leftover.txt").write_text("old")
        second = ws.create_workspace(settings, 902, src, ["a.txt"], {"base_commit": None})
        self.assertFalse((Path(second["path"]) / "leftover.txt").exists())
        self.assertTrue((outside / "keep.txt").is_file())


if __name__ == "__main__":
    unittest.main()
