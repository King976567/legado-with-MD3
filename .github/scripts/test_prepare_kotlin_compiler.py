import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import urllib.request

import prepare_kotlin_compiler as prepare


CONTENTS = b"verified official Kotlin compiler fixture"
SHA1 = hashlib.sha1(CONTENTS).hexdigest()
SHA256 = hashlib.sha256(CONTENTS).hexdigest()
POM = b'''<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <!-- Preserve the official POM, including its dependencies and formatting. -->
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.jetbrains.kotlin</groupId>
  <artifactId>kotlin-compiler-embeddable</artifactId>
  <version>2.4.20</version>
  <dependencies>
    <dependency>
      <groupId>org.jetbrains.kotlin</groupId>
      <artifactId>kotlin-stdlib</artifactId>
      <version>2.4.20</version>
      <scope>runtime</scope>
    </dependency>
  </dependencies>
</project>
'''
POM_SHA1 = hashlib.sha1(POM).hexdigest()


class CompilerPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.versions = self.root / "libs.versions.toml"
        self.versions.write_text('[versions]\nkotlin = "2.4.20"\n', encoding="utf-8")
        self.config = prepare.load_compiler(self.versions, self.root / "gradle")
        self.asset = {"id": 587891620, "size": len(CONTENTS),
                      "name": self.config.filename, "digest": f"sha256:{SHA256}",
                      "url": f"{prepare.API}/releases/assets/587891620"}
        self.metadata = mock.patch.object(prepare, "get_release_asset", return_value=self.asset)
        self.checksum = mock.patch.object(prepare, "get_maven_sha1", return_value=SHA1)
        self.pom = mock.patch.object(prepare, "get_maven_pom", return_value=(POM, POM_SHA1))

    def repository_files(self):
        destination = prepare.artifact_path(self.config)
        return {destination: CONTENTS, destination.with_suffix(".pom"): POM,
                destination.with_suffix(".jar.sha1"): (SHA1 + "\n").encode(),
                destination.with_suffix(".pom.sha1"): (POM_SHA1 + "\n").encode()}

    def write_repository(self):
        files = self.repository_files()
        for path, contents in files.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(contents)
        return files

    def assert_repository(self):
        expected = self.repository_files()
        actual = {path: path.read_bytes() for path in prepare.repository_path(self.config).rglob("*")
                  if path.is_file()}
        self.assertEqual(expected, actual)
        self.assertFalse((self.config.gradle_user_home / "caches").exists())

    def test_version_catalog_selects_kotlin_version_and_original_artifact_name(self):
        self.assertEqual("2.4.20", self.config.version)
        self.assertEqual("kotlin-compiler-embeddable-2.4.20.jar", self.config.filename)

    def test_missing_or_unsafe_version_is_rejected(self):
        for text in ('[versions]\n', '[versions]\nkotlin="../escape"',
                     '[versions]\nkotlin="2.4.20-RC"', '[versions]\nkotlin=24', 'versions="invalid"'):
            with self.subTest(text=text):
                self.versions.write_text(text, encoding="utf-8")
                with self.assertRaises(prepare.PrepareError):
                    prepare.load_compiler(self.versions, self.root)

    def test_dedicated_repository_has_standard_maven_layout_and_rejects_escape(self):
        repository = self.root / "gradle/codex-official-kotlin-maven"
        expected = repository / "org/jetbrains/kotlin/kotlin-compiler-embeddable/2.4.20" / self.config.filename
        self.assertEqual(repository, prepare.repository_path(self.config))
        self.assertEqual(expected, prepare.artifact_path(self.config))
        self.assertNotIn("files-2.1", str(expected))
        for config in (prepare.Compiler("../../../../../../escape", self.config.filename, self.config.gradle_user_home),
                       prepare.Compiler(self.config.version, str(self.root / "escaped.jar"), self.config.gradle_user_home)):
            with self.subTest(config=config):
                with self.assertRaises(prepare.PrepareError):
                    prepare.artifact_path(config)
        self.assertFalse(self.config.gradle_user_home.exists())

    def test_release_metadata_selects_exact_official_asset(self):
        with mock.patch.object(prepare, "_open", return_value=io.BytesIO(json.dumps({"assets": [self.asset]}).encode())) as opened:
            self.assertEqual(self.asset, prepare.get_release_asset(self.config, "private-token"))
        self.assertEqual(f"{prepare.API}/releases/tags/v2.4.20", opened.call_args.args[0])
        self.assertEqual("application/vnd.github+json", opened.call_args.args[2])

    def test_release_rejects_duplicate_or_invalid_metadata(self):
        invalid = [{"assets": []}, {"assets": [self.asset, self.asset]}, {"assets": None}, {}]
        for changes in ({"id": True}, {"id": 0}, {"size": -1}, {"size": True},
                        {"url": "https://evil.invalid/asset"},
                        {"url": f"{prepare.API}/releases/assets/123"},
                        {"digest": None}, {"digest": "sha256:wrong"}):
            invalid.append({"assets": [self.asset | changes]})
        for document in invalid:
            with self.subTest(document=document):
                with mock.patch.object(prepare, "_open", return_value=io.BytesIO(json.dumps(document).encode())):
                    with self.assertRaises(prepare.PrepareError):
                        prepare.get_release_asset(self.config, "token")

    def test_maven_jar_checksum_and_pom_requests_never_carry_github_authorization(self):
        opener = mock.Mock()
        opener.open.side_effect = [io.BytesIO((SHA1 + "\n").encode()), io.BytesIO(POM),
                                   io.BytesIO((POM_SHA1 + "\n").encode())]
        with mock.patch.object(prepare.urllib.request, "build_opener", return_value=opener):
            self.assertEqual(SHA1, prepare.get_maven_sha1(self.config))
            self.assertEqual((POM, POM_SHA1), prepare.get_maven_pom(self.config))
        pom_filename = self.config.filename.removesuffix(".jar") + ".pom"
        self.assertEqual([f"{prepare.MAVEN}/2.4.20/{filename}" for filename in
                          (self.config.filename + ".sha1", pom_filename, pom_filename + ".sha1")],
                         [call.args[0].full_url for call in opener.open.call_args_list])
        for call in opener.open.call_args_list:
            self.assertFalse(call.args[0].has_header("Authorization"))

    def test_maven_sha1_rejects_malformed_or_extended_checksums(self):
        for value in ("bad", SHA1 + " extra", "f" * 41, "f" * 257):
            opener = mock.Mock()
            opener.open.return_value = io.BytesIO(value.encode())
            with mock.patch.object(prepare.urllib.request, "build_opener", return_value=opener):
                with self.assertRaises(prepare.PrepareError):
                    prepare.get_maven_sha1(self.config)
        with mock.patch.object(prepare, "_maven_bytes", return_value=(SHA1.upper() + "\n").encode()):
            self.assertEqual(SHA1, prepare.get_maven_sha1(self.config))

    def test_maven_checksum_redirect_rejects_other_hosts_and_credentials(self):
        request = urllib.request.Request(prepare.MAVEN + "/file.sha1")
        for target in ("https://github.com/asset", "http://repo.maven.apache.org/a",
                       "https://user:pass@repo.maven.apache.org/a", "https://repo.maven.apache.org:444/a",
                       "https://repo.maven.apache.org.evil.invalid/a"):
            with self.subTest(target=target):
                with self.assertRaises(prepare.PrepareError):
                    prepare.MavenRedirectHandler().redirect_request(request, None, 302, "Found", {}, target)

    def test_official_pom_bytes_and_dependencies_are_preserved(self):
        with mock.patch.object(prepare, "_maven_bytes", side_effect=[POM, POM_SHA1.encode()]) as fetched:
            self.assertEqual((POM, POM_SHA1), prepare.get_maven_pom(self.config))
        filename = self.config.filename.removesuffix(".jar") + ".pom"
        self.assertEqual([mock.call(self.config, filename, 1024 * 1024),
                          mock.call(self.config, filename + ".sha1", 256)], fetched.call_args_list)

    def test_pom_must_match_its_official_maven_checksum(self):
        with mock.patch.object(prepare, "_maven_bytes", side_effect=[POM, b"f" * 40]):
            with self.assertRaises(prepare.PrepareError):
                prepare.get_maven_pom(self.config)

    def test_pom_requires_valid_xml_exact_coordinates_and_no_relocation(self):
        invalid = [b"<project", POM.replace(b' xmlns="http://maven.apache.org/POM/4.0.0"', b""),
                   POM.replace(b"org.jetbrains.kotlin", b"untrusted.group", 1),
                   POM.replace(b"kotlin-compiler-embeddable", b"other-artifact", 1),
                   POM.replace(b"2.4.20", b"2.4.21", 1),
                   POM.replace(b"</project>", b"<distributionManagement><relocation>"
                               b"<artifactId>other-artifact</artifactId></relocation>"
                               b"</distributionManagement></project>")]
        for contents in invalid:
            with self.subTest(contents=contents):
                sha1 = hashlib.sha1(contents).hexdigest().encode()
                with mock.patch.object(prepare, "_maven_bytes", side_effect=[contents, sha1]):
                    with self.assertRaises(prepare.PrepareError):
                        prepare.get_maven_pom(self.config)

    def test_complete_repository_requires_jar_size_and_both_hashes_before_skipping(self):
        self.write_repository()
        with self.metadata, self.checksum, self.pom, mock.patch.object(prepare, "download_compiler") as download:
            self.assertEqual("cached-repository", prepare.prepare_compiler(self.config, "token", cached_only=True))
        download.assert_not_called()
        cases = [(self.asset | {"size": len(CONTENTS) + 1}, SHA1),
                 (self.asset | {"digest": "sha256:" + "f" * 64}, SHA1),
                 (self.asset, "f" * 40)]
        for asset, sha1 in cases:
            with self.subTest(asset=asset, sha1=sha1):
                with mock.patch.object(prepare, "get_release_asset", return_value=asset), self.pom:
                    with mock.patch.object(prepare, "get_maven_sha1", return_value=sha1):
                        with mock.patch.object(prepare, "download_compiler") as download:
                            with self.assertRaises(prepare.PrepareError):
                                prepare.prepare_compiler(self.config, cached_only=True)
                        download.assert_not_called()
                self.assert_repository()

    def test_cached_only_rejects_missing_or_invalid_pom_and_checksum_companions(self):
        destination = prepare.artifact_path(self.config)
        companions = [path for path in self.repository_files() if path != destination]
        for companion in companions:
            for missing in (True, False):
                with self.subTest(companion=companion.name, missing=missing):
                    self.write_repository()
                    if missing:
                        companion.unlink()
                    else:
                        companion.write_bytes(b"invalid cached companion")
                    before = {path: path.read_bytes() for path in destination.parent.iterdir()}
                    with self.metadata, self.checksum, self.pom, mock.patch.object(prepare, "download_compiler") as download:
                        with self.assertRaises(prepare.PrepareError):
                            prepare.prepare_compiler(self.config, cached_only=True)
                    download.assert_not_called()
                    self.assertEqual(before, {path: path.read_bytes() for path in destination.parent.iterdir()})

    def test_missing_companions_are_repaired_without_redownloading_valid_jar(self):
        destination = prepare.artifact_path(self.config)
        destination.parent.mkdir(parents=True)
        destination.write_bytes(CONTENTS)
        with self.metadata, self.checksum, self.pom, mock.patch.object(prepare, "download_compiler") as download:
            self.assertEqual("prepared-repository", prepare.prepare_compiler(self.config))
        download.assert_not_called()
        self.assert_repository()

    def test_correct_size_with_wrong_hash_is_replaced_after_verified_download(self):
        destination = prepare.artifact_path(self.config)
        destination.parent.mkdir(parents=True)
        destination.write_bytes(b"x" * len(CONTENTS))
        with self.metadata, self.checksum, self.pom, mock.patch.object(prepare, "_open", return_value=io.BytesIO(CONTENTS)) as opened:
            self.assertEqual("prepared-repository", prepare.prepare_compiler(self.config, "private-token"))
        self.assertEqual((self.asset["url"], "private-token", "application/octet-stream"), opened.call_args.args)
        self.assert_repository()

    def test_bad_sha1_sha256_or_length_keeps_previous_jar_and_cleans_part(self):
        destination = prepare.artifact_path(self.config)
        cases = [(CONTENTS[:-1], self.asset, SHA1), (CONTENTS + b"extra", self.asset, SHA1),
                 (CONTENTS, self.asset | {"digest": "sha256:" + "f" * 64}, SHA1),
                 (CONTENTS, self.asset, "f" * 40), (b"x" * len(CONTENTS), self.asset, SHA1)]
        for contents, asset, sha1 in cases:
            with self.subTest(size=len(contents), digest=asset["digest"]):
                previous = self.write_repository()
                previous[destination] = b"previous compiler file"
                destination.write_bytes(previous[destination])
                with mock.patch.object(prepare, "get_release_asset", return_value=asset), self.pom:
                    with mock.patch.object(prepare, "get_maven_sha1", return_value=sha1):
                        with mock.patch.object(prepare, "_open", return_value=io.BytesIO(contents)):
                            with self.assertRaises(prepare.PrepareError):
                                prepare.prepare_compiler(self.config)
                self.assertEqual(previous, {path: path.read_bytes() for path in destination.parent.iterdir()})

    def test_valid_download_cannot_succeed_when_maven_sha1_disagrees(self):
        different_sha1 = "f" * 40
        destination = prepare.artifact_path(self.config)
        with self.metadata, self.pom, mock.patch.object(prepare, "get_maven_sha1", return_value=different_sha1):
            with mock.patch.object(prepare, "_open", return_value=io.BytesIO(CONTENTS)):
                with self.assertRaises(prepare.PrepareError):
                    prepare.prepare_compiler(self.config)
        self.assertFalse(destination.exists())
        self.assertFalse(list(prepare.repository_path(self.config).rglob("*.pom")))
        self.assertFalse(list(destination.parent.glob("*.part")))

    def test_cached_only_does_not_download_or_create_missing_repository_directories(self):
        with self.metadata, self.checksum, self.pom, mock.patch.object(prepare, "download_compiler") as download:
            with self.assertRaises(prepare.PrepareError):
                prepare.prepare_compiler(self.config, cached_only=True)
        download.assert_not_called()
        self.assertFalse(self.config.gradle_user_home.exists())

    def test_shared_api_client_checks_official_redirects_and_strips_authorization(self):
        from prepare_gradle_distribution import OfficialRedirectHandler
        request = urllib.request.Request(self.asset["url"], headers={"Authorization": "Bearer secret"})
        redirected = OfficialRedirectHandler().redirect_request(request, None, 302, "Found", {},
            "https://release-assets.githubusercontent.com/asset?signature=private")
        self.assertFalse(redirected.has_header("Authorization"))
        with self.assertRaises(prepare.PrepareError):
            OfficialRedirectHandler().redirect_request(request, None, 302, "Found", {}, "https://evil.invalid/asset")

    def test_cli_failure_does_not_print_signed_url_or_token(self):
        with mock.patch.object(prepare, "prepare_compiler", side_effect=prepare.PrepareError("signedURL?token=secret")):
            with contextlib.redirect_stderr(io.StringIO()) as output:
                self.assertEqual(1, prepare.main(["--versions-file", str(self.versions)]))
        self.assertNotIn("signedURL", output.getvalue())
        self.assertNotIn("secret", output.getvalue())


if __name__ == "__main__":
    unittest.main()
