"""Functional checks for the app's current built-in help chat."""

from datetime import datetime, timedelta
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
from orchestrator import handle_chat


class ChatHandoffTests(unittest.TestCase):
    def setUp(self):
        self.payload = {
            "schema_version": 1,
            "message_id": "demo-chat-001",
            "body": "DEMO-PRIVATE-QUERY \u20b9120 \U0001f6d2",
        }

    def test_chat_returns_correlated_help_without_echoing_input(self):
        result = json.loads(handle_chat(json.dumps(self.payload)))
        self.assertEqual(result["message_id"], "demo-chat-001")
        self.assertEqual(result["schema_version"], 1)
        self.assertEqual(result["status"], "capabilities")
        self.assertIn("SMS capture tab", result["reply"])
        self.assertIn("not available yet", result["reply"])
        self.assertNotIn("DEMO-PRIVATE-QUERY", json.dumps(result))
        self.assertEqual(set(result), {
            "schema_version", "message_id", "status", "reply", "processed_at_utc",
        })
        processed_at = datetime.fromisoformat(result["processed_at_utc"])
        self.assertEqual(processed_at.utcoffset(), timedelta(0))

    def test_chat_requires_all_contract_fields(self):
        for field in self.payload:
            with self.subTest(missing_field=field):
                incomplete = dict(self.payload)
                del incomplete[field]
                with self.assertRaises(ValueError):
                    handle_chat(json.dumps(incomplete))

    def test_chat_rejects_invalid_field_values(self):
        cases = {
            "schema_version": [2, True, "1", 1.0],
            "message_id": ["", " \t", None, 123],
            "body": ["", " \n", None, 123],
        }
        for field, values in cases.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    invalid = dict(self.payload, **{field: value})
                    with self.assertRaises(ValueError):
                        handle_chat(json.dumps(invalid))

    def test_chat_rejects_bad_json_with_a_generic_error(self):
        for value in (None, 123, {}, b"{}", "null", "[]", '"demo"',
                      '{"body": "DEMO-PRIVATE-QUERY"'):
            with self.subTest(value=value):
                with self.assertRaises(ValueError) as caught:
                    handle_chat(value)
                self.assertEqual(str(caught.exception), "Invalid chat handoff payload.")
                self.assertNotIn("DEMO-PRIVATE-QUERY", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
