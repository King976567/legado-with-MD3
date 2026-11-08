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

    def test_actual_gradle_sha1_artifact_cache_layout(self):
        sha1 = "23b042894dcabe39edd0f8bd30b7be0f25fe9933"
        expected = self.root / "gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.20" / sha1 / self.config.filename
        self.assertEqual(expected, prepare.artifact_path(self.config, sha1))
        self.assertEqual("abc", prepare.artifact_path(self.config, "0" * 37 + "abc").parent.name)
        for bad in ("../escape", "a" * 39, "G" * 40):
            with self.assertRaises(prepare.PrepareError):
                prepare.artifact_path(self.config, bad)

    def test_release_metadata_selects_exact_official_asset(self):
        with mock.patch.object(prepare, "_open", return_value=io.BytesIO(json.dumps({"assets": [self.asset]}).encode())) as opened:
            self.assertEqual(self.asset, prepare.get_release_asset(self.config, "private-token"))
        self.assertEqual(f"{prepare.API}/releases/tags/v2.4.20", opened.call_args.args[0])
        self.assertEqual("application/vnd.github+json", opened.call_args.args[2])

    def test_release_rejects_duplicate_or_invalid_metadata(self):
        invalid = [{"assets": []}, {"assets": [self.asset, self.asset]}]
        for changes in ({"id": True}, {"size": -1}, {"size": True},
                        {"url": "https://evil.invalid/asset"},
                        {"url": f"{prepare.API}/releases/assets/123"},
                        {"digest": None}, {"digest": "sha256:wrong"}):
            invalid.append({"assets": [self.asset | changes]})
        for document in invalid:
            with self.subTest(document=document):
                with mock.patch.object(prepare, "_open", return_value=io.BytesIO(json.dumps(document).encode())):
                    with self.assertRaises(prepare.PrepareError):
                        prepare.get_release_asset(self.config, "token")

    def test_maven_sha1_request_never_carries_github_authorization(self):
        opener = mock.Mock()
        opener.open.return_value = io.BytesIO((SHA1 + "\n").encode())
        with mock.patch.object(prepare.urllib.request, "build_opener", return_value=opener):
            self.assertEqual(SHA1, prepare.get_maven_sha1(self.config))
        request = opener.open.call_args.args[0]
        self.assertTrue(request.full_url.startswith(prepare.MAVEN))
        self.assertTrue(request.full_url.endswith(self.config.filename + ".sha1"))
        self.assertFalse(request.has_header("Authorization"))

    def test_maven_sha1_rejects_malformed_or_extended_checksums(self):
        for value in ("bad", SHA1 + " extra", "f" * 41):
            opener = mock.Mock()
            opener.open.return_value = io.BytesIO(value.encode())
            with mock.patch.object(prepare.urllib.request, "build_opener", return_value=opener):
                with self.assertRaises(prepare.PrepareError):
                    prepare.get_maven_sha1(self.config)

    def test_maven_checksum_redirect_rejects_other_hosts_and_credentials(self):
        request = urllib.request.Request(prepare.MAVEN + "/file.sha1")
        for target in ("https://github.com/asset", "http://repo.maven.apache.org/a",
                       "https://user:pass@repo.maven.apache.org/a", "https://repo.maven.apache.org.evil.invalid/a"):
            with self.subTest(target=target):
                with self.assertRaises(prepare.PrepareError):
                    prepare.MavenRedirectHandler().redirect_request(request, None, 302, "Found", {}, target)

    def test_existing_jar_requires_size_and_both_checksums_before_skipping(self):
        destination = prepare.artifact_path(self.config, SHA1)
        destination.parent.mkdir(parents=True)
        destination.write_bytes(CONTENTS)
        with self.metadata, self.checksum, mock.patch.object(prepare, "download_compiler") as download:
            self.assertEqual("cached-jar", prepare.prepare_compiler(self.config, "token"))
        download.assert_not_called()

    def test_correct_size_with_wrong_hash_is_replaced_after_verified_download(self):
        destination = prepare.artifact_path(self.config, SHA1)
        destination.parent.mkdir(parents=True)
        destination.write_bytes(b"x" * len(CONTENTS))
        with self.metadata, self.checksum, mock.patch.object(prepare, "_open", return_value=io.BytesIO(CONTENTS)):
            self.assertEqual("downloaded", prepare.prepare_compiler(self.config))
        self.assertEqual(CONTENTS, destination.read_bytes())
        self.assertEqual([destination], list(destination.parent.iterdir()))
        self.assertFalse((self.config.gradle_user_home / "caches/modules-2/metadata-2.107").exists())

    def test_bad_sha1_sha256_or_length_keeps_previous_jar_and_cleans_part(self):
        destination = prepare.artifact_path(self.config, SHA1)
        destination.parent.mkdir(parents=True)
        previous = b"old invalid cache file"
        cases = [(CONTENTS[:-1], self.asset, SHA1), (CONTENTS + b"extra", self.asset, SHA1),
                 (CONTENTS, self.asset | {"digest": "sha256:" + "f" * 64}, SHA1),
                 (b"x" * len(CONTENTS), self.asset, SHA1)]
        for contents, asset, sha1 in cases:
            with self.subTest(size=len(contents), digest=asset["digest"]):
                destination.write_bytes(previous)
                with mock.patch.object(prepare, "get_release_asset", return_value=asset), self.checksum:
                    with mock.patch.object(prepare, "_open", return_value=io.BytesIO(contents)):
                        with self.assertRaises(prepare.PrepareError):
                            prepare.prepare_compiler(self.config)
                self.assertEqual(previous, destination.read_bytes())
                self.assertEqual([destination], list(destination.parent.iterdir()))

    def test_valid_download_cannot_succeed_when_maven_sha1_disagrees(self):
        different_sha1 = "f" * 40
        destination = prepare.artifact_path(self.config, different_sha1)
        with self.metadata, mock.patch.object(prepare, "get_maven_sha1", return_value=different_sha1):
            with mock.patch.object(prepare, "_open", return_value=io.BytesIO(CONTENTS)):
                with self.assertRaises(prepare.PrepareError):
                    prepare.prepare_compiler(self.config)
        self.assertFalse(destination.exists())
        self.assertFalse(list(destination.parent.glob("*.part")))

    def test_cached_only_does_not_download_or_create_missing_cache_directories(self):
        with self.metadata, self.checksum, mock.patch.object(prepare, "download_compiler") as download:
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
