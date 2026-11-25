from __future__ import annotations

import importlib.util
import os
import json
import hashlib
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "tools" / "release_mobile.py"
SPEC = importlib.util.spec_from_file_location("release_mobile", SCRIPT)
release = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(release)


class MobileReleaseTests(unittest.TestCase):
    def test_template_version_metadata_matches_android_build_defaults(self):
        root = Path(__file__).resolve().parents[1]
        version = json.loads((root / "version.json").read_text(encoding="utf-8"))
        asset_version = json.loads((root / "app/src/main/assets/version.json").read_text(encoding="utf-8"))
        app_config = json.loads((root / "mobile-app.json").read_text(encoding="utf-8"))
        gradle = (root / "app/build.gradle").read_text(encoding="utf-8")
        self.assertEqual(version, asset_version)
        _, _, feed_path, feed_url = release.update_target(app_config, app_config["app_id"])
        self.assertEqual(f"feeds/{app_config['app_id']}/stable.json", feed_path)
        self.assertEqual(feed_url, app_config["update_manifest_url"])
        asset_config = json.loads((root / "app/src/main/assets/mobile-app.json").read_text(encoding="utf-8"))
        self.assertEqual(feed_url, asset_config["update_manifest_url"])
        self.assertFalse((root / "updates/stable.json").exists())
        self.assertIn("currentVersion.version_code).toString().toInteger()", gradle)
        self.assertIn("currentVersion.version).toString()", gradle)

    def test_version_and_tag_are_stable_and_safe(self):
        self.assertEqual("candidate-v1.2.0-42", release.candidate_tag("1.2.0", 42))
        for name, code in (("../1.0", 2), ("1.0", 0), ("1.0", True), ("a\\b", 3)):
            with self.subTest(name=name, code=code), self.assertRaises(ValueError):
                release.validate_version(name, code)

    def test_android_update_version_code_must_advance_over_stable(self):
        release._require_version_code_advance(9, 8)
        with self.assertRaisesRegex(release.ReleaseError, "higher than the stable"):
            release._require_version_code_advance(8, 8)
        with self.assertRaisesRegex(release.ReleaseError, "higher than the stable"):
            release._require_version_code_advance(7, 8)

    def test_repository_must_be_https_github_url(self):
        self.assertEqual(("siny-dev", "ma"), release.parse_repository_url("https://github.com/siny-dev/ma/"))
        for url in ("http://github.com/siny-dev/ma", "https://user@github.com/siny-dev/ma", "https://example.test/siny-dev/ma"):
            with self.subTest(url=url), self.assertRaises(release.ReleaseError):
                release.parse_repository_url(url)

    def test_release_metadata_binds_app_package_version_and_digest(self):
        digest = "a" * 64
        data = release.build_release_metadata("alarm-123", "com.siny.apps.alarm_123", "2.1.0", 9,
                                              "alarm-2.1.0-9.apk", digest)
        self.assertEqual("alarm-123", data["app_id"])
        self.assertEqual(9, data["version_code"])
        self.assertEqual(digest, data["sha256"])
        with self.assertRaises(ValueError):
            release.build_release_metadata("alarm-123", "com.siny.apps.alarm_123", "2.1.0", 9,
                                           "../app.apk", digest)

    def test_public_asset_download_rejects_arbitrary_hosts_and_paths(self):
        for url in ("http://github.com/o/r/releases/download/t/a.apk",
                    "https://example.test/o/r/releases/download/t/a.apk",
                    "https://user@github.com/o/r/releases/download/t/a.apk",
                    "https://github.com/o/r/archive/main.zip"):
            with self.subTest(url=url), self.assertRaises(release.ReleaseError):
                release._download_public_asset(url)

    def test_app_and_github_repository_identity_must_match(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "app").mkdir()
            (root / "app" / "build.gradle").write_text("plugins {}", encoding="utf-8")
            (root / "mobile-app.json").write_text(json.dumps({
                "app_id": "alarm-123", "application_id": "com.siny.apps.alarm_123", "platform": "android"
            }), encoding="utf-8")
            (root / "github-app.json").write_text(json.dumps({
                "app_id": "alarm-123", "application_id": "com.siny.apps.alarm_123",
                "repository_name": "alarm", "repository_url": "https://github.com/siny-dev/alarm",
                "clone_url": "https://github.com/siny-dev/alarm.git"
            }), encoding="utf-8")
            _, _, owner, repo = release._load_identity(root)
            self.assertEqual(("siny-dev", "alarm"), (owner, repo))
            mapping = json.loads((root / "github-app.json").read_text(encoding="utf-8"))
            mapping["repository_name"] = "other-app"
            (root / "github-app.json").write_text(json.dumps(mapping), encoding="utf-8")
            with self.assertRaises(release.ReleaseError):
                release._load_identity(root)

    def test_stable_feed_must_match_app_and_android_package(self):
        mapping = {
            "app_id": "alarm-123",
            "repository_url": "https://github.com/siny-dev/alarm",
            "module_repositories": {"AutoUpdate": {
                "repository": "siny-dev/auto-update",
                "manifest_path": "feeds/alarm-123/stable.json",
            }},
        }

        class Response:
            def __init__(self, data):
                self.data = data
            def __enter__(self):
                return self
            def __exit__(self, *exc):
                return False
            def read(self, size=-1):
                return self.data[:size]

        correct = json.dumps({"schema": 1, "platform": "android", "app_id": "alarm-123",
                              "package_name": "com.siny.apps.alarm_123", "version_code": 8}).encode()
        wrong_package = json.dumps({"schema": 1, "platform": "android", "app_id": "alarm-123",
                                    "package_name": "com.siny.apps.other", "version_code": 8}).encode()
        with patch.object(release.urllib.request, "urlopen", side_effect=[Response(correct), Response(wrong_package)]) as open_url:
            self.assertEqual(8, release._stable_version(mapping, "alarm-123", "com.siny.apps.alarm_123")[1])
            self.assertIn("raw.githubusercontent.com/siny-dev/auto-update/main/feeds/alarm-123/stable.json",
                          open_url.call_args_list[0].args[0].full_url)
            with self.assertRaisesRegex(release.ReleaseError, "identity"):
                release._stable_version(mapping, "alarm-123", "com.siny.apps.alarm_123")

    def test_rollback_candidate_rebuilds_old_source_at_the_next_android_version_code(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "version.json").write_text(json.dumps({"version": "1.0.7"}), encoding="utf-8")
            subprocess.run(["git", "init", str(root)], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(root), "config", "user.name", "Release Test"], check=True)
            subprocess.run(["git", "-C", str(root), "config", "user.email", "release-test@example.invalid"], check=True)
            subprocess.run(["git", "-C", str(root), "add", "version.json"], check=True)
            subprocess.run(["git", "-C", str(root), "commit", "-m", "historical app version"], check=True, capture_output=True)
            source_ref = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], check=True,
                                       capture_output=True, text=True).stdout.strip()
            (root / "version.json").write_text(json.dumps({"version": "1.0.31", "version_code": 1016}), encoding="utf-8")
            identity = ({"app_id": "alarm-123", "application_id": "com.siny.apps.alarm_123"}, {}, "siny-dev", "alarm")
            with (patch.object(release, "_load_identity", return_value=identity),
                  patch.object(release, "_assert_publishable_tree", return_value="main-commit"),
                  patch.object(release, "_stable_version", return_value=({}, 25)),
                  patch.object(release, "_candidate", return_value="candidate-url") as candidate):
                result = release._rollback_candidate(root, source_ref)
            self.assertEqual(("candidate-url", "1.0.7", 1017), result)
            candidate.assert_called_once_with(root, "1.0.7", 1017, source_ref)

    def test_candidate_cannot_be_created_at_the_current_stable_version_code(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "app").mkdir()
            (root / "app" / "build.gradle").write_text("plugins {}", encoding="utf-8")
            (root / "version.json").write_text(json.dumps({"version": "1.0.10", "version_code": 10}), encoding="utf-8")
            (root / "mobile-app.json").write_text(json.dumps({
                "app_id": "alarm-123", "application_id": "com.siny.apps.alarm_123", "platform": "android"
            }), encoding="utf-8")
            (root / "github-app.json").write_text(json.dumps({
                "app_id": "alarm-123", "application_id": "com.siny.apps.alarm_123",
                "repository_name": "alarm", "repository_url": "https://github.com/siny-dev/alarm",
                "clone_url": "https://github.com/siny-dev/alarm.git",
                "module_repositories": {"AutoUpdate": {"repository": "siny-dev/auto-update"}}
            }), encoding="utf-8")
            (root / "updates").mkdir()
            (root / "updates" / "stable.json").write_text(json.dumps({
                "schema": 1, "platform": "android", "app_id": "alarm-123",
                "package_name": "com.siny.apps.alarm_123", "version_code": 8
            }), encoding="utf-8")
            subprocess.run(["git", "init", "-b", "main", str(root)], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(root), "config", "user.name", "Release Test"], check=True)
            subprocess.run(["git", "-C", str(root), "config", "user.email", "release-test@example.invalid"], check=True)
            subprocess.run(["git", "-C", str(root), "add", "--all"], check=True)
            subprocess.run(["git", "-C", str(root), "commit", "-m", "base"], check=True, capture_output=True)
            with (patch.object(release, "_stable_version", return_value=({}, 8)),
                  patch.object(release, "_github_token", side_effect=AssertionError("must stop before GitHub"))):
                with self.assertRaisesRegex(release.ReleaseError, "must be higher"):
                    release._candidate(root, "1.0.8", 8, None)
                with self.assertRaisesRegex(release.ReleaseError, "must not be lower"):
                    release._candidate(root, "1.0.9", 9, None)

    def test_promote_verifies_candidate_then_updates_only_the_stable_feed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "app"
            root.mkdir()
            (root / "app").mkdir()
            (root / "app" / "build.gradle").write_text("plugins {}", encoding="utf-8")
            (root / "updates").mkdir()
            (root / "mobile-app.json").write_text(json.dumps({
                "app_id": "alarm-123", "application_id": "com.siny.apps.alarm_123", "platform": "android"
            }), encoding="utf-8")
            (root / "github-app.json").write_text(json.dumps({
                "app_id": "alarm-123", "application_id": "com.siny.apps.alarm_123",
                "repository_name": "alarm", "repository_url": "https://github.com/siny-dev/alarm",
                "clone_url": "https://github.com/siny-dev/alarm.git",
                "module_repositories": {"AutoUpdate": {"repository": "siny-dev/auto-update"}}
            }), encoding="utf-8")
            stable = {"schema": 1, "platform": "android", "app_id": "alarm-123",
                      "package_name": "com.siny.apps.alarm_123", "version": "1.0.0",
                      "version_code": 1, "apk_url": "", "sha256": ""}
            (root / "updates" / "stable.json").write_text(json.dumps(stable), encoding="utf-8")
            subprocess.run(["git", "init", "-b", "main", str(root)], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(root), "config", "user.name", "Release Test"], check=True)
            subprocess.run(["git", "-C", str(root), "config", "user.email", "release-test@example.invalid"], check=True)
            subprocess.run(["git", "-C", str(root), "add", "--all"], check=True)
            subprocess.run(["git", "-C", str(root), "commit", "-m", "base"], check=True, capture_output=True)
            remote = Path(temporary) / "remote.git"
            subprocess.run(["git", "init", "--bare", str(remote)], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(root), "remote", "add", "origin", str(remote)], check=True)
            subprocess.run(["git", "-C", str(root), "push", "-u", "origin", "main"], check=True, capture_output=True)

            tag = "candidate-alarm-123-v1.0.1-2"
            apk = b"signed test apk bytes"
            digest = hashlib.sha256(apk).hexdigest()
            metadata = {"schema": 1, "app_id": "alarm-123", "package_name": "com.siny.apps.alarm_123",
                        "version": "1.0.1", "version_code": 2, "apk_name": "alarm-1.0.1-2.apk",
                        "sha256": digest}
            asset_prefix = f"https://github.com/siny-dev/auto-update/releases/download/{tag}/"
            candidate = {"draft": False, "prerelease": True, "assets": [
                {"name": "release-metadata.json", "browser_download_url": asset_prefix + "release-metadata.json"},
                {"name": "alarm-1.0.1-2.apk", "browser_download_url": asset_prefix + "alarm-1.0.1-2.apk"},
            ]}
            stable = {"schema": 1, "platform": "android", "app_id": "alarm-123",
                      "package_name": "com.siny.apps.alarm_123", "version": "1.0.0", "version_code": 1,
                      "apk_url": "", "sha256": "", "rollback_versions": [
                          {"version": "0.9.0", "version_code": 1},
                          {"version": "0.8.0", "version_code": 3},
                      ]}
            api_calls = []
            def api(method, url, token, payload=None, *args, **kwargs):
                api_calls.append((method, url, payload))
                if method == "GET" and "/releases/tags/" in url:
                    return candidate
                if method == "GET" and "/contents/feeds/alarm-123/stable.json" in url:
                    return {"sha": "existing-feed-sha"}
                if method == "PUT" and "/contents/feeds/alarm-123/stable.json" in url:
                    return {"commit": {"html_url": "https://github.com/siny-dev/auto-update/commit/feed"}}
                raise AssertionError(f"unexpected GitHub request: {method} {url}")

            with (patch.object(release, "_github_token", return_value="test-token"),
                  patch.object(release, "_stable_version", return_value=(stable, 1)),
                  patch.object(release, "_api", side_effect=api),
                  patch.object(release, "_download_public_asset", side_effect=[json.dumps(metadata).encode(), apk]),
                  patch.object(release, "_verify_apk", return_value=digest)):
                result = release._promote(root, tag)

            self.assertEqual("https://github.com/siny-dev/auto-update/commit/feed", result)
            self.assertTrue(all("siny-dev/auto-update" in url for _, url, _ in api_calls))
            promote_call = next(payload for method, url, payload in api_calls
                                if method == "PUT" and "/contents/feeds/alarm-123/stable.json" in url)
            updated = json.loads(__import__("base64").b64decode(promote_call["content"]))
            self.assertEqual(2, updated["version_code"])
            self.assertEqual("1.0.1", updated["version"])
            self.assertEqual(digest, updated["sha256"])
            self.assertEqual(asset_prefix + "alarm-1.0.1-2.apk", updated["apk_url"])
            self.assertEqual([3], [item["version_code"] for item in updated["rollback_versions"]])

    def test_register_rollback_publishes_only_verified_historical_source_candidates(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "app"
            root.mkdir()
            (root / "app").mkdir()
            (root / "app" / "build.gradle").write_text("plugins {}", encoding="utf-8")
            (root / "mobile-app.json").write_text(json.dumps({
                "app_id": "alarm-123", "application_id": "com.siny.apps.alarm_123", "platform": "android"
            }), encoding="utf-8")
            (root / "github-app.json").write_text(json.dumps({
                "app_id": "alarm-123", "application_id": "com.siny.apps.alarm_123",
                "repository_name": "alarm", "repository_url": "https://github.com/siny-dev/alarm",
                "clone_url": "https://github.com/siny-dev/alarm.git",
                "module_repositories": {"AutoUpdate": {"repository": "siny-dev/auto-update"}}
            }), encoding="utf-8")
            tag = "candidate-alarm-123-v0.9.0-9"
            apk = b"verified rollback apk"
            digest = hashlib.sha256(apk).hexdigest()
            metadata = {"schema": 1, "app_id": "alarm-123", "package_name": "com.siny.apps.alarm_123",
                        "version": "0.9.0", "version_code": 9, "apk_name": "alarm-0.9.0-9.apk",
                        "sha256": digest, "rollback_source": {"ref": "main", "version": "0.9.0",
                                                                   "source_version_code": 5}}
            prefix = f"https://github.com/siny-dev/auto-update/releases/download/{tag}/"
            candidate = {"draft": False, "prerelease": True, "assets": [
                {"name": "release-metadata.json", "browser_download_url": prefix + "release-metadata.json"},
                {"name": "alarm-0.9.0-9.apk", "browser_download_url": prefix + "alarm-0.9.0-9.apk"},
            ]}
            stable = {"schema": 1, "platform": "android", "app_id": "alarm-123",
                      "package_name": "com.siny.apps.alarm_123", "version": "1.0.0", "version_code": 8,
                      "rollback_versions": []}
            api_calls = []
            def api(method, url, token, payload=None, *args, **kwargs):
                api_calls.append((method, url, payload))
                if method == "GET" and "/releases/tags/" in url:
                    return candidate
                if method == "GET" and "/contents/feeds/alarm-123/stable.json" in url:
                    return {"sha": "existing-feed-sha"}
                if method == "PUT" and "/contents/feeds/alarm-123/stable.json" in url:
                    return {"commit": {"html_url": "https://github.com/siny-dev/auto-update/commit/rollback"}}
                raise AssertionError(f"unexpected GitHub request: {method} {url}")

            with (patch.object(release, "_assert_publishable_tree", return_value="main-commit"),
                  patch.object(release, "_stable_version", return_value=(stable, 8)),
                  patch.object(release, "_github_token", return_value="test-token"),
                  patch.object(release, "_api", side_effect=api),
                  patch.object(release, "_download_public_asset", side_effect=[json.dumps(metadata).encode(), apk]),
                  patch.object(release, "_verify_apk", return_value=digest)):
                result = release._register_rollback(root, tag)

            self.assertEqual("https://github.com/siny-dev/auto-update/commit/rollback", result)
            publish_call = next(payload for method, url, payload in api_calls
                                if method == "PUT" and "/contents/feeds/alarm-123/stable.json" in url)
            updated = json.loads(__import__("base64").b64decode(publish_call["content"]))
            self.assertEqual("1.0.0", updated["version"])
            self.assertEqual(8, updated["version_code"])
            self.assertEqual([{"version": "0.9.0", "source_version_code": 5, "version_code": 9,
                               "apk_url": prefix + "alarm-0.9.0-9.apk", "sha256": digest, "release_tag": tag}],
                             updated["rollback_versions"])

    @unittest.skipUnless(os.name == "nt", "Windows DPAPI is used by the release signing-key store.")
    def test_windows_dpapi_protects_and_recovers_a_temporary_password(self):
        original = b"temporary-test-password-not-a-real-key"
        protected = release._windows_dpapi(original, True)
        self.assertNotEqual(original, protected)
        self.assertEqual(original, release._windows_dpapi(protected, False))


if __name__ == "__main__":
    unittest.main()
