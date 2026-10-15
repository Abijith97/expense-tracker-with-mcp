"""Offline signing safety checks: fake key bytes/tools, no real signing secrets."""

import base64
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("demo_signing", ROOT / "ci/sign_demo_apk.py")
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)
DIGEST = "a" * 64


class DemoSigningTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.source = self.directory / "input.apk"
        self.source.write_bytes(b"synthetic-unsigned-apk")
        self.output = self.directory / "output.apk"
        self.fingerprint = self.directory / "certificate.sha256"
        self.fingerprint.write_text(DIGEST, encoding="utf-8")
        self.environment = {
            "ANDROID_DEMO_KEYSTORE_BASE64": base64.b64encode(b"fake-test-key").decode("ascii"),
            "ANDROID_DEMO_KEYSTORE_PASSWORD": "synthetic-password",
        }
        self.key_paths = []

    def fake_tool(self, command, **kwargs):
        self.assertNotIn(self.environment["ANDROID_DEMO_KEYSTORE_PASSWORD"], command)
        self.assertNotIn("ANDROID_DEMO_KEYSTORE_BASE64", kwargs["env"])
        self.assertTrue(kwargs["capture_output"])
        if command[1] == "sign":
            key = Path(command[command.index("--ks") + 1])
            self.key_paths.append(key)
            self.assertEqual(key.read_bytes(), b"fake-test-key")
            Path(command[command.index("--out") + 1]).write_bytes(b"synthetic-signed-apk")
            return subprocess.CompletedProcess(command, 0, "", "")
        self.assertEqual(command[1], "verify")
        return subprocess.CompletedProcess(command, 0, "Signer #1 certificate SHA-256 digest: " + DIGEST + "\n", "")

    def sign(self):
        return signing.sign_apk("fake-apksigner", self.source, self.output, self.fingerprint, self.environment)

    def test_verified_output_and_public_report_contain_no_secret_and_temp_key_is_removed(self):
        with patch.object(signing.subprocess, "run", side_effect=self.fake_tool) as run:
            report = self.sign()
        self.assertEqual(run.call_count, 2)
        self.assertEqual(report["certificate_sha256"], DIGEST)
        self.assertEqual(self.output.read_bytes(), b"synthetic-signed-apk")
        self.assertEqual(self.source.read_bytes(), b"synthetic-unsigned-apk")
        saved = self.output.with_suffix(".signing.json").read_text(encoding="utf-8")
        self.assertEqual(json.loads(saved), report)
        self.assertNotIn("password", saved)
        self.assertNotIn("fake-test-key", saved)
        self.assertTrue(all(not key.exists() for key in self.key_paths))

    def test_missing_secrets_fail_closed_without_invoking_tools(self):
        for missing in self.environment:
            with self.subTest(missing=missing):
                environment = dict(self.environment)
                del environment[missing]
                with patch.object(signing.subprocess, "run") as run:
                    with self.assertRaisesRegex(ValueError, "required"):
                        signing.sign_apk("fake", self.source, self.output, self.fingerprint, environment)
                    run.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_malformed_or_empty_key_encoding_is_not_echoed(self):
        for encoded in ("invalid-private-value!", ""):
            environment = dict(self.environment, ANDROID_DEMO_KEYSTORE_BASE64=encoded)
            with patch.object(signing.subprocess, "run") as run:
                with self.assertRaises(ValueError) as caught:
                    signing.sign_apk("fake", self.source, self.output, self.fingerprint, environment)
                self.assertNotIn("invalid-private-value", str(caught.exception))
                run.assert_not_called()

    def test_wrong_certificate_never_publishes_apk_or_report(self):
        self.fingerprint.write_text("b" * 64, encoding="utf-8")
        with patch.object(signing.subprocess, "run", side_effect=self.fake_tool):
            with self.assertRaisesRegex(ValueError, "pinned"):
                self.sign()
        self.assertFalse(self.output.exists())
        self.assertFalse(self.output.with_suffix(".signing.json").exists())
        self.assertTrue(all(not key.exists() for key in self.key_paths))

    def test_tool_failures_are_sanitized_and_never_publish(self):
        error = subprocess.CalledProcessError(1, "fake", stderr="private provider error")
        with patch.object(signing.subprocess, "run", side_effect=error):
            with self.assertRaises(ValueError) as caught:
                self.sign()
        self.assertNotIn("private provider error", str(caught.exception))
        self.assertFalse(self.output.exists())

    def test_missing_or_multiple_signers_are_rejected(self):
        for text in ("unsigned", "Signer #1 certificate SHA-256 digest: " + DIGEST + "\n" +
                     "Signer #2 certificate SHA-256 digest: " + DIGEST + "\n"):
            with self.assertRaisesRegex(ValueError, "exactly one"):
                signing.certificate_digest(text)

    def test_in_place_existing_output_and_invalid_pin_are_rejected(self):
        with self.assertRaises(ValueError):
            signing.sign_apk("fake", self.source, self.source, self.fingerprint, self.environment)
        self.output.write_bytes(b"retain-this")
        with self.assertRaises(ValueError):
            self.sign()
        self.assertEqual(self.output.read_bytes(), b"retain-this")
        self.output.unlink()
        self.fingerprint.write_text("not a digest", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.sign()
