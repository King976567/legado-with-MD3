import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import urllib.error
import urllib.request


SCRIPT = Path(__file__).with_name("prepare_gradle_distribution.py")
SPEC = importlib.util.spec_from_file_location("prepare_gradle_distribution", SCRIPT)
prepare = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = prepare
SPEC.loader.exec_module(prepare)

URL = "https://services.gradle.org/distributions/gradle-9.8.0-bin.zip"
CONTENTS = b"a verified distribution archive"
SHA = hashlib.sha256(CONTENTS).hexdigest()


class DistributionPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.home = self.root / "gradle-user-home"
        self.properties = self.root / "gradle-wrapper.properties"
        self.values = {
            "distributionBase": "GRADLE_USER_HOME",
            "distributionPath": "wrapper/dists",
            "distributionSha256Sum": SHA,
            "distributionUrl": URL.replace(":", "\\:", 1),
            "zipStoreBase": "GRADLE_USER_HOME",
            "zipStorePath": "wrapper/dists",
        }
        self.write_properties()

    def write_properties(self, **changes):
        values = self.values | changes
        self.properties.write_text(
            "\n".join(f"{key}={value}" for key, value in values.items()) + "\n",
            encoding="utf-8",
        )

    def config(self):
        return prepare.load_distribution(self.properties, self.home)

    def asset(self):
        return {
            "id": 586068844,
            "name": "gradle-9.8.0-bin.zip",
            "size": len(CONTENTS),
            "digest": f"sha256:{SHA}",
            "url": f"{prepare.API}/releases/assets/586068844",
        }

    def metadata(self, asset=None):
        return io.BytesIO(json.dumps({"assets": [asset or self.asset()]}).encode())

    def test_official_wrapper_url_uses_original_url_hash_and_user_home(self):
        config = self.config()
        self.assertEqual(URL, config.url)
        self.assertEqual("9.8.0", config.version)
        self.assertEqual("gradle-9.8.0-bin.zip", config.filename)
        self.assertEqual("3m7h6ceboy5k31n8kzwzuxssm", prepare.url_hash(URL))
        self.assertEqual(
            self.home / "wrapper/dists/gradle-9.8.0-bin/3m7h6ceboy5k31n8kzwzuxssm/gradle-9.8.0-bin.zip",
            config.zip_path,
        )

    def test_wrapper_requires_pinned_sha256(self):
        for value in ("", "a" * 63, "g" * 64):
            with self.subTest(value=value):
                self.write_properties(distributionSha256Sum=value)
                with self.assertRaises(prepare.PrepareError):
                    self.config()

    def test_nonofficial_or_ambiguous_distribution_url_is_rejected(self):
        for url in (
            "http://services.gradle.org/distributions/gradle-9.8.0-bin.zip",
            "https://example.com/gradle-9.8.0-bin.zip",
            "https://services.gradle.org.evil.invalid/distributions/gradle-9.8.0-bin.zip",
            "https://services.gradle.org/distributions/gradle-9.8.0-bin.zip?token=hidden",
            "https://services.gradle.org/distributions/gradle-9.8.0-bin.zip#fragment",
        ):
            with self.subTest(url=url):
                self.write_properties(distributionUrl=url)
                with self.assertRaises(prepare.PrepareError):
                    self.config()

    def test_cache_bases_and_paths_cannot_escape_gradle_user_home(self):
        for changes in (
            {"distributionBase": "PROJECT"},
            {"zipStoreBase": "PROJECT"},
            {"distributionPath": "../escape"},
            {"zipStorePath": "/absolute"},
            {"zipStorePath": "C:/absolute"},
        ):
            with self.subTest(changes=changes):
                self.write_properties(**changes)
                with self.assertRaises(prepare.PrepareError):
                    self.config()

    def test_matching_zip_is_reused_without_network_or_marker_writes(self):
        config = self.config()
        config.zip_path.parent.mkdir(parents=True)
        config.zip_path.write_bytes(CONTENTS)
        with mock.patch.object(prepare, "get_release_asset") as get_asset:
            self.assertEqual("cached-zip", prepare.prepare_distribution(config))
        get_asset.assert_not_called()
        self.assertFalse(Path(str(config.zip_path) + ".ok").exists())

    def test_unmarked_distribution_cannot_bypass_download(self):
        config = self.config()
        config.distribution_dir.mkdir(parents=True)
        with mock.patch.object(prepare, "get_release_asset", side_effect=prepare.PrepareError("network attempted")) as get_asset:
            with self.assertRaisesRegex(prepare.PrepareError, "network attempted"):
                prepare.prepare_distribution(config)
        get_asset.assert_called_once()

    def test_wrapper_marked_complete_distribution_skips_network(self):
        config = self.config()
        (config.distribution_dir / "bin").mkdir(parents=True)
        (config.distribution_dir / "lib").mkdir()
        (config.distribution_dir / "bin/gradle").write_bytes(b"launcher")
        (config.distribution_dir / "lib/gradle-launcher-9.8.0.jar").write_bytes(b"launcher jar")
        Path(str(config.zip_path) + ".ok").write_bytes(b"")
        with mock.patch.object(prepare, "get_release_asset") as get_asset:
            self.assertEqual("cached-distribution", prepare.prepare_distribution(config))
        get_asset.assert_not_called()
        self.assertFalse(config.zip_path.exists())

    def test_marker_alone_cannot_bypass_download(self):
        config = self.config()
        config.zip_path.parent.mkdir(parents=True)
        Path(str(config.zip_path) + ".ok").write_bytes(b"")
        with mock.patch.object(prepare, "get_release_asset", side_effect=prepare.PrepareError("incomplete")):
            with self.assertRaisesRegex(prepare.PrepareError, "incomplete"):
                prepare.prepare_distribution(config)

    def test_official_download_replaces_bad_zip_atomically_without_ok_marker(self):
        config = self.config()
        config.zip_path.parent.mkdir(parents=True)
        config.zip_path.write_bytes(b"incomplete earlier download")
        with mock.patch.object(prepare, "_open", side_effect=[self.metadata(), io.BytesIO(CONTENTS)]) as opened:
            self.assertEqual("downloaded", prepare.prepare_distribution(config, "secret-token"))
        self.assertEqual(CONTENTS, config.zip_path.read_bytes())
        self.assertEqual(2, opened.call_count)
        self.assertEqual("application/octet-stream", opened.call_args.args[2])
        self.assertEqual([], list(config.zip_path.parent.glob("*.part")))
        self.assertFalse(Path(str(config.zip_path) + ".ok").exists())

    def test_invalid_asset_metadata_rejected_before_downloading(self):
        config = self.config()
        for changes in (
            {"digest": "sha256:" + "0" * 64},
            {"url": "https://example.com/stolen.zip"},
            {"url": f"{prepare.API}/releases/assets/999"},
            {"id": True},
            {"size": -1},
            {"size": True},
        ):
            with self.subTest(changes=changes):
                with mock.patch.object(prepare, "_open", return_value=self.metadata(self.asset() | changes)) as opened:
                    with self.assertRaises(prepare.PrepareError):
                        prepare.prepare_distribution(config)
                self.assertEqual(1, opened.call_count)
                self.assertFalse(config.zip_path.exists())

    def test_download_checksum_failure_keeps_existing_zip_and_removes_part(self):
        config = self.config()
        config.zip_path.parent.mkdir(parents=True)
        previous = b"previous invalid ZIP"
        config.zip_path.write_bytes(previous)
        with mock.patch.object(prepare, "_open", side_effect=[self.metadata(), io.BytesIO(b"x" * len(CONTENTS))]):
            with self.assertRaisesRegex(prepare.PrepareError, "SHA-256"):
                prepare.prepare_distribution(config)
        self.assertEqual(previous, config.zip_path.read_bytes())
        self.assertEqual([], list(config.zip_path.parent.glob("*.part")))

    def test_download_length_mismatch_is_rejected(self):
        config = self.config()
        for contents in (CONTENTS[:-1], CONTENTS + b"extra"):
            with self.subTest(length=len(contents)):
                with mock.patch.object(prepare, "_open", side_effect=[self.metadata(), io.BytesIO(contents)]):
                    with self.assertRaises(prepare.PrepareError):
                        prepare.prepare_distribution(config)
                self.assertFalse(config.zip_path.exists())
                self.assertEqual([], list(config.zip_path.parent.glob("*.part")))

    def test_network_failures_retry_three_times_with_sanitized_error(self):
        with mock.patch.object(prepare, "_open", side_effect=urllib.error.URLError("https://signed.invalid/?secret=token")) as opened:
            with mock.patch.object(prepare.time, "sleep") as sleep:
                with self.assertRaisesRegex(prepare.PrepareError, "three attempts") as error:
                    prepare.get_release_asset(self.config(), "private-token")
        self.assertEqual(3, opened.call_count)
        self.assertEqual(2, sleep.call_count)
        self.assertNotIn("signed", str(error.exception))
        self.assertNotIn("token", str(error.exception))

    def test_redirect_to_official_cdn_strips_authorization(self):
        request = urllib.request.Request(f"{prepare.API}/releases/assets/586068844", headers={"Authorization": "Bearer secret"})
        redirected = prepare.OfficialRedirectHandler().redirect_request(
            request, None, 302, "Found", {}, "https://release-assets.githubusercontent.com/path?signature=private"
        )
        self.assertFalse(redirected.has_header("Authorization"))
        self.assertTrue(request.has_header("Authorization"))

    def test_redirect_only_allows_official_https_hosts(self):
        request = urllib.request.Request(f"{prepare.API}/releases/assets/586068844")
        for target in (
            "https://example.com/asset",
            "https://release-assets.githubusercontent.com.evil.invalid/asset",
            "http://release-assets.githubusercontent.com/asset",
            "https://user:password@release-assets.githubusercontent.com/asset",
        ):
            with self.subTest(target=target):
                with self.assertRaises(prepare.PrepareError):
                    prepare.OfficialRedirectHandler().redirect_request(request, None, 302, "Found", {}, target)

    def test_cli_never_prints_raw_network_errors_or_credentials(self):
        with mock.patch.object(prepare, "prepare_distribution", side_effect=prepare.PrepareError("signedURL?token=secret")):
            with contextlib.redirect_stderr(io.StringIO()) as output:
                self.assertEqual(1, prepare.main(["--properties", str(self.properties), "--gradle-user-home", str(self.home)]))
        self.assertNotIn("signedURL", output.getvalue())
        self.assertNotIn("secret", output.getvalue())


if __name__ == "__main__":
    unittest.main()
