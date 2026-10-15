"""Offline release publication tests. No GitHub writes, credentials or real APKs."""

import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("demo_publishing", ROOT / "ci/publish_demo_apk.py")
publishing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publishing)
COMMIT = "a" * 40


class DemoPublishingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.apk = self.directory / "app-debug.apk"
        self.apk.write_bytes(b"synthetic-verified-apk")
        self.report = self.apk.with_suffix(".signing.json")
        self.pin = self.directory / "certificate.sha256"
        self.pin.write_text("b" * 64, encoding="utf-8")
        self.report.write_text(json.dumps({"certificate_sha256": "b" * 64,
            "apk_sha256": hashlib.sha256(self.apk.read_bytes()).hexdigest()}), encoding="utf-8")

    def existing(self, draft=False):
        return {"target_commitish": COMMIT, "draft": draft, "assets": [
            {"name": name, "digest": "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()}
            for name, path in ((publishing.APK_NAME, self.apk), (publishing.REPORT_NAME, self.report))
        ]}

    def publish(self):
        return publishing.publish("owner/repo", 14, COMMIT, "12345", self.apk, self.pin)

    def test_new_release_is_draft_until_both_verified_assets_are_uploaded(self):
        with patch.object(publishing, "release", side_effect=[None, None, self.existing(True)]), \
                patch.object(publishing, "gh", return_value="") as gh:
            self.assertIn("Published", self.publish())
        calls = [call.args for call in gh.call_args_list]
        self.assertIn("--draft", calls[0])
        self.assertEqual([call[1] for call in calls], ["create", "upload", "upload", "edit"])
        self.assertIn("--draft=false", calls[-1])
        self.assertIn("--latest", calls[-1])
        self.assertTrue(all("--clobber" not in call for call in calls))

    def test_failed_upload_never_changes_latest_or_deletes_old_assets(self):
        with patch.object(publishing, "release", side_effect=[None, None]), \
                patch.object(publishing, "gh", side_effect=["", subprocess.CalledProcessError(1, "gh")]) as gh:
            with self.assertRaises(subprocess.CalledProcessError):
                self.publish()
        self.assertFalse(any(call.args[1] in {"edit", "delete"} for call in gh.call_args_list))

    def test_old_rerun_cannot_replace_a_newer_demo(self):
        with patch.object(publishing, "release", return_value={"tag_name": "android-demo-15"}), \
                patch.object(publishing, "gh") as gh:
            self.assertIn("newer", self.publish())
            gh.assert_not_called()

    def test_identical_published_rerun_is_idempotent(self):
        with patch.object(publishing, "release", side_effect=[None, self.existing()]), \
                patch.object(publishing, "gh") as gh:
            self.assertIn("already", self.publish())
            gh.assert_not_called()

    def test_existing_draft_resumes_only_missing_assets(self):
        partial = self.existing(True)
        partial["assets"] = partial["assets"][:1]
        with patch.object(publishing, "release", side_effect=[None, partial, self.existing(True)]), \
                patch.object(publishing, "gh", return_value="") as gh:
            self.publish()
        self.assertEqual([call.args[1] for call in gh.call_args_list], ["upload", "edit"])

    def test_inconsistent_commit_or_assets_are_never_overwritten(self):
        for changed in ("commit", "asset"):
            existing = self.existing(True)
            if changed == "commit":
                existing["target_commitish"] = "c" * 40
            else:
                existing["assets"][0]["digest"] = "sha256:" + "d" * 64
            with patch.object(publishing, "release", side_effect=[None, existing]), \
                    patch.object(publishing, "gh") as gh:
                with self.assertRaises(ValueError):
                    self.publish()
                gh.assert_not_called()

    def test_tampered_apk_or_certificate_cannot_reach_github(self):
        self.apk.write_bytes(b"changed")
        with patch.object(publishing, "gh") as gh:
            with self.assertRaisesRegex(ValueError, "APK"):
                self.publish()
            gh.assert_not_called()
        self.pin.write_text("c" * 64, encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Certificate"):
            self.publish()

    def test_only_not_found_is_treated_as_an_absent_release(self):
        draft = {"tag_name": "android-demo-14", "draft": True}
        with patch.object(publishing, "gh", return_value=json.dumps([[], [draft]])):
            self.assertEqual(publishing.release("owner/repo", "tags/android-demo-14"), draft)
            self.assertIsNone(publishing.release("owner/repo", "tags/android-demo-13"))
        with patch.object(publishing, "gh", side_effect=subprocess.CalledProcessError(1, "gh", stderr="HTTP 404")):
            self.assertIsNone(publishing.release("owner/repo", "latest"))
        with patch.object(publishing, "gh", side_effect=subprocess.CalledProcessError(1, "gh", stderr="HTTP 403")):
            with self.assertRaises(subprocess.CalledProcessError):
                publishing.release("owner/repo", "latest")
