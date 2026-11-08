import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET


SCRIPT = Path(__file__).with_name("verify_test_shards.py")
SPEC = importlib.util.spec_from_file_location("verify_test_shards", SCRIPT)
verifier = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = verifier
SPEC.loader.exec_module(verifier)
SHA = "a" * 40


class ShardVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.count = 4
        # Include top-level tests, nested classes, and a helper which emits no XML.
        self.candidates = [f"io/legado/app/Test{index}.class" for index in range(12)]
        self.candidates += ["io/legado/app/Test0$Nested.class", "io/legado/app/Helper.class"]
        for index in range(self.count):
            directory = self.root / f"shard-{index}"
            (directory / "junit").mkdir(parents=True)
            selected = [
                candidate for candidate in self.candidates
                if verifier.shard_for_candidate(candidate, self.count) == index
            ]
            self.write_manifest(index, {
                "schemaVersion": 1,
                "taskPath": verifier.TASK_PATH,
                "variant": "appComicDebug",
                "shardIndex": index,
                "shardCount": self.count,
                "sourceSha": SHA,
                "allCandidates": self.candidates,
                "selectedCandidates": selected,
            })
            tests = [candidate for candidate in selected if "$" not in candidate and "Helper" not in candidate]
            suite = ET.Element("testsuite", tests=str(len(tests)), failures="0", errors="0")
            for candidate in tests:
                ET.SubElement(suite, "testcase", classname=candidate[:-6].replace("/", "."), name="works")
            ET.ElementTree(suite).write(directory / "junit" / "TEST-example.xml", encoding="utf-8")

    def manifest_path(self, index):
        return self.root / f"shard-{index}" / "shard-manifest.json"

    def read_manifest(self, index):
        return json.loads(self.manifest_path(index).read_text(encoding="utf-8"))

    def write_manifest(self, index, data):
        self.manifest_path(index).write_text(json.dumps(data), encoding="utf-8")

    def mutate_manifest(self, index, **values):
        data = self.read_manifest(index)
        data.update(values)
        self.write_manifest(index, data)

    def report(self, index):
        return self.root / f"shard-{index}" / "junit" / "TEST-example.xml"

    def verify(self):
        return verifier.verify_shards(self.root, self.count, SHA)

    def test_complete_disjoint_classes_pass_with_helpers_and_nested_classes(self):
        result = self.verify()
        self.assertEqual(4, result.shard_count)
        self.assertEqual(len(self.candidates), result.candidate_count)
        self.assertEqual(12, result.test_case_count)
        self.assertEqual(4, result.xml_count)

    def test_missing_manifest_fails(self):
        self.manifest_path(3).unlink()
        with self.assertRaisesRegex(verifier.VerificationError, "Expected 4"):
            self.verify()

    def test_duplicate_index_fails(self):
        self.mutate_manifest(3, shardIndex=2)
        with self.assertRaisesRegex(verifier.VerificationError, "Duplicate shard index"):
            self.verify()

    def test_duplicate_manifest_path_fails(self):
        paths = [self.manifest_path(index) for index in range(3)] + [self.manifest_path(0)]
        with mock.patch.object(Path, "rglob", return_value=iter(paths)):
            with self.assertRaisesRegex(verifier.VerificationError, "Duplicate manifest path"):
                self.verify()

    def test_original_class_list_must_match_on_every_runner(self):
        self.mutate_manifest(3, allCandidates=self.candidates + ["io/Extra.class"])
        with self.assertRaisesRegex(verifier.VerificationError, "original candidate class lists differ"):
            self.verify()

    def test_overlapping_selection_fails(self):
        duplicate = self.read_manifest(0)["selectedCandidates"][0]
        selected = self.read_manifest(1)["selectedCandidates"] + [duplicate]
        self.mutate_manifest(1, selectedCandidates=selected)
        with self.assertRaisesRegex(verifier.VerificationError, "overlap"):
            self.verify()

    def test_missing_class_fails(self):
        selected = self.read_manifest(0)["selectedCandidates"]
        self.mutate_manifest(0, selectedCandidates=selected[1:])
        with self.assertRaisesRegex(verifier.VerificationError, "union does not cover"):
            self.verify()

    def test_nested_class_cannot_be_moved_to_another_shard(self):
        nested = "io/legado/app/Test0$Nested.class"
        original_index = verifier.shard_for_candidate(nested, self.count)
        other_index = (original_index + 1) % self.count
        original = self.read_manifest(original_index)["selectedCandidates"]
        other = self.read_manifest(other_index)["selectedCandidates"]
        self.mutate_manifest(original_index, selectedCandidates=[item for item in original if item != nested])
        self.mutate_manifest(other_index, selectedCandidates=other + [nested])
        with self.assertRaisesRegex(verifier.VerificationError, "wrong hash assignment"):
            self.verify()

    def test_source_sha_task_and_variant_are_bound(self):
        for field, value in (("sourceSha", "b" * 40), ("taskPath", ":app:testAppCoreDebugUnitTest"), ("variant", "appCoreDebug")):
            original = self.read_manifest(0)
            with self.subTest(field=field):
                self.mutate_manifest(0, **{field: value})
                with self.assertRaises(verifier.VerificationError):
                    self.verify()
                self.write_manifest(0, original)

    def test_bad_candidate_paths_and_duplicate_entries_fail(self):
        original = self.read_manifest(0)
        for value in ("../escape.class", "/absolute.class", "a\\b.class", "a/./b.class", "a//b.class", "C:/b.class", "not-a-class.kt"):
            with self.subTest(path=value):
                self.mutate_manifest(0, allCandidates=self.candidates + [value])
                with self.assertRaisesRegex(verifier.VerificationError, "invalid allCandidates"):
                    self.verify()
        self.write_manifest(0, original)
        self.mutate_manifest(0, allCandidates=self.candidates + [self.candidates[0]])
        with self.assertRaisesRegex(verifier.VerificationError, "duplicate allCandidates"):
            self.verify()

    def test_missing_or_empty_junit_fails(self):
        self.report(0).unlink()
        with self.assertRaisesRegex(verifier.VerificationError, "reports are missing"):
            self.verify()
        self.report(0).write_text('<testsuite tests="0" failures="0" errors="0"/>', encoding="utf-8")
        with self.assertRaisesRegex(verifier.VerificationError, "no testcases"):
            self.verify()

    def test_suite_failure_counter_fails_even_without_failure_node(self):
        tree = ET.parse(self.report(0))
        tree.getroot().set("failures", "1")
        tree.write(self.report(0), encoding="utf-8")
        with self.assertRaisesRegex(verifier.VerificationError, "failures=1"):
            self.verify()

    def test_testsuite_counters_are_required(self):
        original = self.report(0).read_bytes()
        for field in ("tests", "failures", "errors"):
            with self.subTest(field=field):
                tree = ET.parse(self.report(0))
                del tree.getroot().attrib[field]
                tree.write(self.report(0), encoding="utf-8")
                with self.assertRaisesRegex(verifier.VerificationError, "testsuite lacks"):
                    self.verify()
                self.report(0).write_bytes(original)

    def test_testcase_total_must_match_declared_tests(self):
        tree = ET.parse(self.report(0))
        tree.getroot().set("tests", "0")
        tree.write(self.report(0), encoding="utf-8")
        with self.assertRaisesRegex(verifier.VerificationError, "disagrees with"):
            self.verify()

    def test_error_node_fails_even_when_counters_claim_zero(self):
        tree = ET.parse(self.report(0))
        ET.SubElement(tree.getroot().find("testcase"), "error", message="broken")
        tree.write(self.report(0), encoding="utf-8")
        with self.assertRaisesRegex(verifier.VerificationError, "contains error"):
            self.verify()

    def test_duplicate_testcase_report_is_rejected(self):
        (self.report(0).parent / "TEST-copy.xml").write_bytes(self.report(0).read_bytes())
        with self.assertRaisesRegex(verifier.VerificationError, "Duplicate JUnit testcase"):
            self.verify()

    def test_foreign_shard_junit_is_rejected(self):
        tree = ET.parse(self.report(1))
        foreign = ET.parse(self.report(0)).getroot().find("testcase")
        tree.getroot().append(foreign)
        tree.getroot().set("tests", str(len(tree.getroot().findall("testcase"))))
        tree.write(self.report(1), encoding="utf-8")
        with self.assertRaisesRegex(verifier.VerificationError, "outside selected candidates|Duplicate JUnit testcase"):
            self.verify()

    def test_testsuites_wrapper_does_not_double_count_cases(self):
        for index in range(self.count):
            root = ET.Element("testsuites", failures="0", errors="0")
            root.append(ET.parse(self.report(index)).getroot())
            ET.ElementTree(root).write(self.report(index), encoding="utf-8")
        self.assertEqual(12, self.verify().test_case_count)

    def test_nested_testclass_xml_belongs_to_its_top_level_owner(self):
        nested = "io/legado/app/Test0$Nested.class"
        index = verifier.shard_for_candidate(nested, self.count)
        tree = ET.parse(self.report(index))
        ET.SubElement(tree.getroot(), "testcase", classname="io.legado.app.Test0$Nested", name="nested works")
        tree.getroot().set("tests", str(len(tree.getroot().findall("testcase"))))
        tree.write(self.report(index), encoding="utf-8")
        self.assertEqual(13, self.verify().test_case_count)

    def test_namespaced_junit_report_is_accepted(self):
        for index in range(self.count):
            tree = ET.parse(self.report(index))
            for element in tree.getroot().iter():
                element.tag = "{urn:junit}" + element.tag
            tree.write(self.report(index), encoding="utf-8")
        self.assertEqual(12, self.verify().test_case_count)

    def test_invalid_xml_is_reported(self):
        self.report(0).write_text("<testsuite>", encoding="utf-8")
        with self.assertRaisesRegex(verifier.VerificationError, "Cannot parse JUnit"):
            self.verify()

    def test_manifest_boolean_index_is_not_a_shard_number(self):
        self.mutate_manifest(0, shardIndex=False)
        with self.assertRaisesRegex(verifier.VerificationError, "invalid shardIndex"):
            self.verify()

    def test_cli_exit_status_is_fail_closed(self):
        arguments = ["--artifacts-dir", str(self.root), "--shard-count", "4", "--source-sha", SHA]
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, verifier.main(arguments))
        self.assertIn("exactly once", output.getvalue())
        self.manifest_path(3).unlink()
        with contextlib.redirect_stderr(io.StringIO()) as output:
            self.assertEqual(1, verifier.main(arguments))
        self.assertIn("verification failed", output.getvalue())


class HashCompatibilityTest(unittest.TestCase):
    def test_java_signed_hash_and_utf16_code_units(self):
        self.assertEqual(0, verifier.java_string_hash(""))
        self.assertEqual(96354, verifier.java_string_hash("abc"))
        self.assertEqual(-2147483648, verifier.java_string_hash("polygenelubricants"))
        self.assertEqual(1772899, verifier.java_string_hash("😀"))

    def test_nested_families_share_the_same_assignment(self):
        for suffix in (".class", "$Nested.class", "$Nested$1.class", "$1.class"):
            self.assertEqual(
                verifier.shard_for_candidate("io/Test.class", 4),
                verifier.shard_for_candidate("io/Test" + suffix, 4),
            )


if __name__ == "__main__":
    unittest.main()
