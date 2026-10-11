"""Offline checks for test infrastructure; no emulator, SMS or paid HTTP needed."""

import importlib.util
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "app/src/main/python"))


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


driver = load("sms_ci_driver", ROOT / "ci/run_android_system_tests.py")
fake = load("sms_ci_fake", ROOT / "app/src/androidTest/assets/system_test_transport.py")
from orchestrator import classify_sms, identify_sms_category


class SystemTestHarnessTests(unittest.TestCase):
    def tearDown(self):
        fake.uninstall()

    def payload(self, scenario):
        return json.dumps({"schema_version": 1, "message_id": "synthetic", "sender": "5550100",
                           "body": driver.sms_body(scenario), "sent_at_ms": 0, "received_at_ms": 1})

    def classify(self, scenario):
        return json.loads(classify_sms(self.payload(scenario), fake.CLASSIFICATION_KEY))

    def category(self, scenario):
        return json.loads(identify_sms_category(self.payload(scenario), fake.CATEGORY_KEY))

    def test_stale_unknown_and_duplicate_shape_markers_cannot_inject_arbitrary_sms(self):
        token = "a" * 32
        self.assertIsNone(driver.scenario_from_line("unrelated", token))
        self.assertIsNone(driver.scenario_from_line(driver.MARKER + "old|happy", token))
        self.assertIsNone(driver.scenario_from_line(driver.MARKER + token + "|happy|extra", token))
        self.assertEqual(driver.scenario_from_line(driver.MARKER + token + "|happy", token), "happy")
        with self.assertRaises(ValueError):
            driver.scenario_from_line(driver.MARKER + token + "|shell command", token)

    def test_emulator_guard_rejects_phone_and_non_emulated_serial(self):
        with patch.object(driver, "adb") as adb:
            with self.assertRaises(RuntimeError):
                driver.require_emulator("34141JEHN02320")
            adb.assert_not_called()
        with patch.object(driver, "adb", return_value="0"):
            with self.assertRaises(RuntimeError):
                driver.require_emulator("emulator-5554")
        with patch.object(driver, "adb", return_value="1"):
            driver.require_emulator("emulator-5554")
        with patch.object(driver.subprocess, "check_output", return_value="List of devices attached\nemulator-5554\tdevice\n"):
            driver.require_only_device("emulator-5554")
        with patch.object(driver.subprocess, "check_output", return_value="List of devices attached\nemulator-5554\tdevice\nphone\tdevice\n"):
            with self.assertRaises(RuntimeError):
                driver.require_only_device("emulator-5554")

    def test_scenarios_agree_and_sms_are_short_synthetic_messages(self):
        self.assertEqual(driver.SCENARIOS, fake.SCENARIOS)
        for scenario in driver.SCENARIOS:
            self.assertLess(len(driver.sms_body(scenario)), 140)
        with self.assertRaises(ValueError):
            driver.sms_body("unknown")

    def test_reset_handles_fresh_emulator_and_only_clears_exact_installed_package(self):
        for packages in ("", "package:" + driver.APP_ID + ".test"):
            with patch.object(driver, "adb", return_value=packages) as adb:
                driver.reset_installed_app("emulator-5554")
                self.assertEqual(adb.call_count, 1)
        with patch.object(driver, "adb", side_effect=["package:" + driver.APP_ID, "Success"]) as adb:
            driver.reset_installed_app("emulator-5554")
            adb.assert_called_with("emulator-5554", "shell", "pm", "clear", driver.APP_ID)
        with patch.object(driver, "adb", side_effect=["package:" + driver.APP_ID, "Failed"]):
            with self.assertRaises(RuntimeError):
                driver.reset_installed_app("emulator-5554")

    def test_real_pipeline_uses_fake_http_and_correct_keys_for_happy_and_uncertain(self):
        for scenario in ("happy", "uncertain"):
            with self.subTest(scenario=scenario):
                fake.install(scenario)
                self.assertIs(self.classify(scenario)["is_expenditure"], True)
                category = self.category(scenario)
                self.assertEqual(category["category"], "Not sure" if scenario == "uncertain" else "utilities")
                self.assertEqual(json.loads(fake.calls_json()), ["expenditure_classification", "spending_category"])

    def test_other_and_malformed_are_distinct_outcomes(self):
        fake.install("other")
        self.assertIs(self.classify("other")["is_expenditure"], False)
        fake.install("malformed")
        result = self.classify("malformed")
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["error_code"], "invalid_response")

    def test_timeouts_and_retry_script_reach_real_transport_error_handler(self):
        fake.install("timeout")
        self.assertEqual(self.classify("timeout")["error_code"], "timeout")
        fake.install("retry")
        self.assertEqual(self.classify("retry")["error_code"], "timeout")
        self.assertIs(self.classify("retry")["is_expenditure"], True)

    def test_category_authentication_failure_is_not_uncertainty(self):
        fake.install("category_failure")
        self.assertIs(self.classify("category_failure")["is_expenditure"], True)
        self.assertEqual(self.category("category_failure")["error_code"], "authentication")

    def test_uninstall_restores_http_and_fake_refuses_unknown_scenarios(self):
        import urllib.request
        original = urllib.request.build_opener
        fake.install("happy")
        self.assertIsNot(urllib.request.build_opener, original)
        fake.uninstall()
        self.assertIs(urllib.request.build_opener, original)
        with self.assertRaises(ValueError):
            fake.install("unknown")
