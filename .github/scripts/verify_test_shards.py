#!/usr/bin/env python3
"""Fail closed unless Comic JVM shards cover the original class candidates once.

Each downloaded artifact contains shard-manifest.json and junit/**/*.xml.  The
manifest records Gradle's original candidate class files, not a source-file glob;
helper classes are intentionally included and remain subject to JUnit discovery.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


TASK_PATH = ":app:testAppComicDebugUnitTest"
VARIANT = "appComicDebug"
SHA_PATTERN = re.compile(r"[0-9a-fA-F]{40}\Z")


class VerificationError(ValueError):
    """A missing or inconsistent artifact must not produce a success status."""


@dataclass(frozen=True)
class ShardManifest:
    path: Path
    index: int
    all_candidates: frozenset[str]
    selected_candidates: frozenset[str]


@dataclass(frozen=True)
class VerificationSummary:
    shard_count: int
    candidate_count: int
    test_case_count: int
    xml_count: int


def top_level_owner(candidate: str) -> str:
    """Keep nested/anonymous classes with their enclosing top-level class."""
    return candidate.removesuffix(".class").split("$", 1)[0]


def java_string_hash(value: str) -> int:
    """Java String.hashCode uses UTF-16 code units and signed 32-bit arithmetic."""
    encoded = value.encode("utf-16-be", errors="surrogatepass")
    result = 0
    for offset in range(0, len(encoded), 2):
        unit = int.from_bytes(encoded[offset : offset + 2], "big")
        result = (31 * result + unit) & 0xFFFFFFFF
    return result if result < 0x80000000 else result - 0x100000000


def shard_for_candidate(candidate: str, shard_count: int) -> int:
    # Python's modulo with a positive divisor equals Java Math.floorMod.
    return java_string_hash(top_level_owner(candidate)) % shard_count


def _require_within(path: Path, root: Path) -> None:
    if not path.resolve().is_relative_to(root.resolve()):
        raise VerificationError(f"Artifact path escapes its root: {path}")


def _candidate_set(value: object, label: str, manifest: Path) -> frozenset[str]:
    if not isinstance(value, list) or not value:
        raise VerificationError(f"{manifest}: {label} must be a non-empty list")
    candidates: set[str] = set()
    for candidate in value:
        if (
            not isinstance(candidate, str)
            or not candidate.endswith(".class")
            or "\\" in candidate
            or ":" in candidate
            or any(ord(char) < 32 for char in candidate)
            or any(part in ("", ".", "..") for part in candidate.split("/"))
        ):
            raise VerificationError(f"{manifest}: invalid {label} class path: {candidate!r}")
        if candidate in candidates:
            raise VerificationError(f"{manifest}: duplicate {label} class: {candidate}")
        candidates.add(candidate)
    return frozenset(candidates)


def _load_manifest(
    path: Path, shard_count: int, source_sha: str, task_path: str
) -> ShardManifest:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise VerificationError(f"Cannot read manifest {path}: {error}") from error
    if not isinstance(data, dict):
        raise VerificationError(f"{path}: manifest must be a JSON object")
    if type(data.get("schemaVersion")) is not int or data["schemaVersion"] != 1:
        raise VerificationError(f"{path}: unsupported schemaVersion")
    if data.get("taskPath") != task_path or data.get("variant") != VARIANT:
        raise VerificationError(f"{path}: unexpected taskPath or Comic variant")
    sha = data.get("sourceSha")
    if not isinstance(sha, str) or not SHA_PATTERN.fullmatch(sha) or sha.lower() != source_sha:
        raise VerificationError(f"{path}: sourceSha does not match the verified commit")
    if type(data.get("shardCount")) is not int or data["shardCount"] != shard_count:
        raise VerificationError(f"{path}: shardCount does not match {shard_count}")
    index = data.get("shardIndex")
    if type(index) is not int or not 0 <= index < shard_count:
        raise VerificationError(f"{path}: invalid shardIndex")
    return ShardManifest(
        path=path,
        index=index,
        all_candidates=_candidate_set(data.get("allCandidates"), "allCandidates", path),
        selected_candidates=_candidate_set(
            data.get("selectedCandidates"), "selectedCandidates", path
        ),
    )


def _tag(element: ET.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def _verify_junit(
    manifest: ShardManifest,
    seen_cases: dict[tuple[str, str], tuple[int, Path]],
) -> tuple[int, int]:
    junit_root = manifest.path.parent / "junit"
    _require_within(junit_root, manifest.path.parent)
    reports = sorted(junit_root.rglob("*.xml")) if junit_root.is_dir() else []
    if not reports:
        raise VerificationError(f"Shard {manifest.index}: JUnit XML reports are missing")
    owners = {top_level_owner(candidate) for candidate in manifest.selected_candidates}
    cases = 0
    for report in reports:
        _require_within(report, manifest.path.parent)
        try:
            root = ET.parse(report).getroot()
        except (OSError, ET.ParseError) as error:
            raise VerificationError(f"Cannot parse JUnit report {report}: {error}") from error
        if _tag(root) not in ("testsuite", "testsuites"):
            raise VerificationError(f"{report}: expected a JUnit testsuite/testsuites root")
        for element in root.iter():
            tag = _tag(element)
            if tag in ("failure", "error"):
                raise VerificationError(f"Shard {manifest.index}: {report} contains {tag}")
            if tag in ("testsuite", "testsuites"):
                for field in ("tests", "failures", "errors"):
                    raw = element.get(field)
                    if raw is None:
                        if tag == "testsuite":
                            raise VerificationError(f"{report}: testsuite lacks {field} count")
                        continue
                    try:
                        count = int(raw)
                    except ValueError as error:
                        raise VerificationError(f"{report}: invalid {field} count") from error
                    if count < 0:
                        raise VerificationError(f"{report}: invalid negative {field} count")
                    if field == "tests":
                        actual = sum(_tag(child) == "testcase" for child in element.iter())
                        if count != actual:
                            raise VerificationError(
                                f"{report}: tests={count} disagrees with {actual} testcase elements"
                            )
                    elif count:
                        raise VerificationError(f"Shard {manifest.index}: {report} reports {field}={count}")
            if tag != "testcase":
                continue
            class_name, name = element.get("classname"), element.get("name")
            if not class_name or not name:
                raise VerificationError(f"{report}: testcase lacks classname or name")
            owner = class_name.replace(".", "/").split("$", 1)[0]
            if owner not in owners:
                raise VerificationError(
                    f"Shard {manifest.index}: JUnit class {class_name} is outside selected candidates"
                )
            key = (class_name, name)
            if key in seen_cases:
                previous_index, previous_report = seen_cases[key]
                raise VerificationError(
                    f"Duplicate JUnit testcase {key!r} in shards {previous_index} and "
                    f"{manifest.index}: {previous_report}, {report}"
                )
            seen_cases[key] = (manifest.index, report)
            cases += 1
    if not cases:
        raise VerificationError(f"Shard {manifest.index}: JUnit reports contain no testcases")
    return cases, len(reports)


def verify_shards(
    artifacts_dir: Path,
    shard_count: int,
    source_sha: str,
    task_path: str = TASK_PATH,
) -> VerificationSummary:
    if type(shard_count) is not int or not 2 <= shard_count <= 4:
        raise VerificationError("shard-count must be between 2 and 4")
    if not SHA_PATTERN.fullmatch(source_sha):
        raise VerificationError("source-sha must be a full 40-character commit SHA")
    source_sha = source_sha.lower()
    paths = sorted(artifacts_dir.rglob("shard-manifest.json")) if artifacts_dir.is_dir() else []
    if len(paths) != shard_count:
        raise VerificationError(f"Expected {shard_count} shard manifests, found {len(paths)}")
    manifests: dict[int, ShardManifest] = {}
    resolved_paths: set[Path] = set()
    for path in paths:
        _require_within(path, artifacts_dir)
        resolved = path.resolve()
        if resolved in resolved_paths:
            raise VerificationError(f"Duplicate manifest path: {path}")
        resolved_paths.add(resolved)
        manifest = _load_manifest(path, shard_count, source_sha, task_path)
        if manifest.index in manifests:
            raise VerificationError(f"Duplicate shard index {manifest.index}")
        manifests[manifest.index] = manifest
    if set(manifests) != set(range(shard_count)):
        raise VerificationError("Shard indices must cover 0 through shard-count minus one")
    original = manifests[0].all_candidates
    selected: set[str] = set()
    for index in range(shard_count):
        manifest = manifests[index]
        if manifest.all_candidates != original:
            raise VerificationError(f"Shard {index}: original candidate class lists differ")
        unknown = manifest.selected_candidates - original
        if unknown:
            raise VerificationError(f"Shard {index}: selected classes are outside original candidates")
        overlap = selected & manifest.selected_candidates
        if overlap:
            raise VerificationError(f"Shard {index}: selected candidate classes overlap: {sorted(overlap)[:3]}")
        selected.update(manifest.selected_candidates)
    if selected != original:
        missing = sorted(original - selected)
        raise VerificationError(f"Selected candidate union does not cover original classes: {missing[:3]}")
    for index, manifest in manifests.items():
        misplaced = [
            candidate for candidate in manifest.selected_candidates
            if shard_for_candidate(candidate, shard_count) != index
        ]
        if misplaced:
            raise VerificationError(f"Shard {index}: class owner has wrong hash assignment: {sorted(misplaced)[:3]}")
    seen_cases: dict[tuple[str, str], tuple[int, Path]] = {}
    cases = reports = 0
    for index in range(shard_count):
        case_count, report_count = _verify_junit(manifests[index], seen_cases)
        cases += case_count
        reports += report_count
    return VerificationSummary(shard_count, len(original), cases, reports)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts-dir", type=Path, required=True)
    parser.add_argument("--shard-count", type=int, required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--task-path", default=TASK_PATH)
    args = parser.parse_args(argv)
    try:
        summary = verify_shards(
            args.artifacts_dir, args.shard_count, args.source_sha, args.task_path
        )
    except VerificationError as error:
        print(f"Shard verification failed: {error}", file=sys.stderr)
        return 1
    print(
        f"Verified {summary.shard_count} Comic shards: {summary.candidate_count} class "
        f"candidates exactly once, {summary.test_case_count} JUnit testcases, "
        f"{summary.xml_count} XML reports, no failures or duplicates."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
