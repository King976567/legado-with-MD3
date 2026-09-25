import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import sync_pr as sync


class SyncBranchTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.previous = os.getcwd()
        os.chdir(self.directory.name)
        sync.git("init", "-b", "main")
        sync.git("config", "user.name", "Test")
        sync.git("config", "user.email", "test@example.invalid")
        self.commit("common.txt", "base", "base")
        sync.git("branch", "nas-md3")

    def tearDown(self):
        os.chdir(self.previous)
        self.directory.cleanup()

    def commit(self, file, content, message):
        Path(file).write_text(content, encoding="utf-8")
        sync.git("add", file)
        sync.git("commit", "-m", message)
        return sync.git("rev-parse", "HEAD")

    def test_no_change_does_not_create_branch(self):
        self.assertIsNone(sync.prepare_branch("nas-md3", "main", "automation/test"))
        self.assertEqual(sync.git("branch", "--show-current"), "main")

    def test_combines_source_and_nas_without_changing_either(self):
        source = self.commit("upstream.txt", "new", "upstream update")
        sync.git("switch", "nas-md3")
        target = self.commit("nas.txt", "NAS", "NAS feature")
        result = sync.prepare_branch(target, source, "automation/test")
        self.assertTrue(sync.ancestor(source, result))
        self.assertTrue(sync.ancestor(target, result))
        self.assertEqual(sync.git("rev-parse", "main"), source)
        self.assertEqual(sync.git("rev-parse", "nas-md3"), target)
        self.assertEqual(Path("nas.txt").read_text(), "NAS")

    def test_updates_existing_review_branch_preserving_manual_fixes(self):
        source = self.commit("upstream.txt", "one", "upstream one")
        first = sync.prepare_branch("nas-md3", source, "automation/first")
        manual = self.commit("manual.txt", "fix", "review fix")
        sync.git("switch", "main")
        second_source = self.commit("upstream.txt", "two", "upstream two")
        result = sync.prepare_branch("nas-md3", second_source, "automation/second", manual)
        self.assertTrue(sync.ancestor(first, result))
        self.assertTrue(sync.ancestor(manual, result))
        self.assertEqual(Path("manual.txt").read_text(), "fix")
        self.assertEqual(Path("upstream.txt").read_text(), "two")

    def test_conflict_aborts_without_changing_target(self):
        source = self.commit("common.txt", "upstream", "source change")
        sync.git("switch", "nas-md3")
        target = self.commit("common.txt", "NAS", "target change")
        with self.assertRaisesRegex(RuntimeError, "common.txt"):
            sync.prepare_branch(target, source, "automation/conflict")
        self.assertEqual(sync.git("rev-parse", "HEAD"), target)
        self.assertEqual(sync.git("rev-parse", "nas-md3"), target)
        self.assertEqual(sync.git("status", "--porcelain"), "")

    def test_identical_content_still_records_source_ancestry(self):
        source = self.commit("same.txt", "same", "source")
        sync.git("switch", "nas-md3")
        target = self.commit("same.txt", "same", "target")
        result = sync.prepare_branch(target, source, "automation/same")
        self.assertTrue(sync.ancestor(source, result))
        self.assertTrue(sync.ancestor(target, result))

    def test_invalid_stage_is_rejected_before_any_git_change(self):
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "King976567/legado-with-MD3", "SYNC_STAGE": "invalid"}):
            with self.assertRaises(ValueError):
                sync.sync()
        self.assertEqual(sync.git("branch", "--show-current"), "main")


if __name__ == "__main__":
    unittest.main()
