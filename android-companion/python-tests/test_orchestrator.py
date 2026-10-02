"""Host-side tests for the exact Python module bundled in the Android APK.

Run from android-companion:
    python -m unittest discover -s python-tests -p "test_*.py" -v

All messages are synthetic. These tests need neither Android nor third-party
packages. The Kotlin bridge is tested separately on an Android device.
"""

import json
from pathlib import Path
import sys
import unittest
from datetime import datetime, timedelta

# Import production code from its APK source directory, rather than copying it.
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
from orchestrator import handle_sms


class SmsHandoffTests(unittest.TestCase):
    def setUp(self):
        """Give each test a fresh, valid version-1 payload."""
        self.payload = {
            "schema_version": 1,
            "message_id": "demo-sms-001",
            "sender": "DEMO-SENDER",
            "body": "Demo purchase: Rs 120 at Cafe Example.",
            "sent_at_ms": 1_700_000_000_000,
            "received_at_ms": 1_700_000_000_100,
        }

    def test_valid_sms_returns_a_receipt_for_the_same_message(self):
        # Arrange: setUp created a valid SMS. Act: pass it to the real entry point.
        receipt = json.loads(handle_sms(json.dumps(self.payload)))

        # Assert observable behavior, allowing version and processing time to vary.
        self.assertEqual(receipt["message_id"], "demo-sms-001")
        self.assertIs(receipt["received"], True)
        self.assertEqual(receipt["schema_version"], 1)
        self.assertEqual(receipt["character_count"], 38)
        self.assertTrue(receipt["python_version"])
        processed_at = datetime.fromisoformat(receipt["processed_at_utc"])
        self.assertEqual(processed_at.utcoffset(), timedelta(0))

    def test_receipt_contains_only_the_public_receipt_fields(self):
        receipt = json.loads(handle_sms(json.dumps(self.payload)))

        self.assertEqual(set(receipt), {
            "schema_version", "received", "message_id", "character_count",
            "python_version", "processed_at_utc",
        })
        self.assertNotIn(self.payload["sender"], json.dumps(receipt))
        self.assertNotIn(self.payload["body"], json.dumps(receipt))

    def test_unicode_counts_code_points_including_emoji(self):
        # The emoji takes two UTF-16 units in Kotlin, but one Python code point.
        self.payload["body"] = "Demo \u20b9120 \U0001f6d2"
        receipt = json.loads(handle_sms(json.dumps(self.payload)))
        self.assertEqual(receipt["character_count"], 11)

    def test_malformed_json_returns_a_generic_error(self):
        sensitive_fragment = '{"body": "DEMO-PRIVATE-CONTENT"'
        with self.assertRaises(ValueError) as caught:
            handle_sms(sensitive_fragment)
        self.assertEqual(str(caught.exception), "Invalid SMS handoff payload.")
        self.assertNotIn("DEMO-PRIVATE-CONTENT", str(caught.exception))

    def test_input_must_be_a_json_string(self):
        for value in (None, 123, True, {}, b"{}"):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    handle_sms(value)

    def test_json_must_describe_an_object(self):
        for value in (None, [], "demo", 123, True):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    handle_sms(json.dumps(value))

    def test_all_payload_fields_are_required(self):
        for field in self.payload:
            with self.subTest(missing_field=field):
                incomplete = dict(self.payload)
                del incomplete[field]
                with self.assertRaises(ValueError):
                    handle_sms(json.dumps(incomplete))

    def test_invalid_field_values_are_rejected(self):
        cases = {
            "schema_version": [2, True, "1", 1.0],
            "message_id": ["", " \t", None, 123],
            "sender": [None, 123],
            "body": ["", " \n\t", None, 123],
            "sent_at_ms": [-1, True, 1.5, "123", None],
            "received_at_ms": [-1, False, 1.5, "123", None],
        }
        for field, values in cases.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    invalid = dict(self.payload, **{field: value})
                    with self.assertRaises(ValueError):
                        handle_sms(json.dumps(invalid))

    def test_zero_timestamps_are_valid(self):
        self.payload.update(sent_at_ms=0, received_at_ms=0)
        receipt = json.loads(handle_sms(json.dumps(self.payload)))
        self.assertIs(receipt["received"], True)


if __name__ == "__main__":
    unittest.main()
