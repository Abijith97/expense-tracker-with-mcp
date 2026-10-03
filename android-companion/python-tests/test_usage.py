"""Offline cost telemetry checks; synthetic counts, never a paid request."""

import copy
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
import expenditure_classifier as classifier
import category_identifier as category
from llm_usage import read_usage, PRICED_MODEL
from orchestrator import classify_sms, identify_sms_category


def response(value=True):
    output = {"is_expenditure": value} if isinstance(value, bool) else value
    return {"status": "completed", "model": PRICED_MODEL, "service_tier": "default",
        "usage": {"input_tokens": 1000, "output_tokens": 100,
            "input_tokens_details": {"cached_tokens": 200, "cache_write_tokens": 100},
            "output_tokens_details": {"reasoning_tokens": 75}},
        "output": [{"type": "message", "content": [
            {"type": "output_text", "text": json.dumps(output)}]}]}


def payload():
    return json.dumps({"schema_version": 1, "message_id": "demo-usage", "sender": "PRIVATE-SENDER",
        "body": "PRIVATE-SMS", "sent_at_ms": 0, "received_at_ms": 1})


class UsageTests(unittest.TestCase):
    def test_calculation_accounts_for_cache_writes_and_does_not_double_count_reasoning(self):
        usage = read_usage(response(), PRICED_MODEL)
        # 700 ordinary, 200 cached, 100 written, 100 total output (includes reasoning).
        self.assertAlmostEqual(usage["estimated_cost_usd"], 0.000289, places=12)
        self.assertEqual(usage["pricing_date"], "2026-10-03")
        self.assertEqual(usage["input_tokens"], 1000)
        self.assertNotIn("output_tokens_details", usage)

    def test_long_context_threshold_prices_the_full_request(self):
        for count, expected in ((272000, .05452), (272001, .1089804)):
            raw = response()
            raw["usage"]["input_tokens"] = count
            raw["usage"]["input_tokens_details"] = {"cached_tokens": 0, "cache_write_tokens": 0}
            self.assertAlmostEqual(read_usage(raw, PRICED_MODEL)["estimated_cost_usd"], expected, places=12)

    def test_missing_or_invalid_usage_is_unknown_not_free(self):
        for raw in (None, {}, {"usage": None}, {"usage": {}}):
            self.assertIsNone(read_usage(raw, PRICED_MODEL))
        for field in ("input_tokens", "output_tokens"):
            for bad in (True, "1", 1.5, -1, 10_000_001, None):
                raw = response()
                raw["usage"][field] = bad
                self.assertIsNone(read_usage(raw, PRICED_MODEL))

    def test_unknown_prices_and_tiers_preserve_counts_without_a_cost(self):
        variants = [("model", "demo-model"), ("model", None),
            ("service_tier", "priority"), ("service_tier", None)]
        for field, value in variants:
            raw = response()
            raw[field] = value
            usage = read_usage(raw, PRICED_MODEL)
            self.assertEqual(usage["input_tokens"], 1000)
            self.assertNotIn("estimated_cost_usd", usage)
        self.assertNotIn("estimated_cost_usd", read_usage(response(), "demo-override"))

    def test_absent_or_impossible_cache_breakdown_does_not_invent_zero(self):
        for details in (None, {}, {"cached_tokens": 0}, {"cache_write_tokens": 0},
            {"cached_tokens": True, "cache_write_tokens": 0},
            {"cached_tokens": 900, "cache_write_tokens": 200}):
            raw = response()
            raw["usage"]["input_tokens_details"] = details
            usage = read_usage(raw, PRICED_MODEL)
            self.assertEqual(usage["output_tokens"], 100)
            self.assertNotIn("estimated_cost_usd", usage)

    def test_both_real_entry_points_pass_safe_telemetry_and_standard_tier(self):
        for helper, entry, value in ((classifier, classify_sms, True),
                                     (category, identify_sms_category, {"category": "utilities", "sub_category": "transport"})):
            with patch.object(helper, "send_request", return_value=response(value)) as request:
                result = json.loads(entry(payload(), "PRIVATE-KEY"))
            self.assertIn(result["status"], ("classified", "identified"))
            self.assertAlmostEqual(result["usage"]["estimated_cost_usd"], .000289)
            self.assertGreaterEqual(result["api_elapsed_ms"], 0)
            self.assertEqual(request.call_args.args[0]["service_tier"], "default")
            self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertEqual(classifier.build_request("Demo", "demo-model")["service_tier"], "default")

    def test_refused_incomplete_or_bad_decisions_keep_reported_costs(self):
        raw = response()
        variants = [copy.deepcopy(raw) for _ in range(3)]
        variants[0]["status"] = "incomplete"
        variants[1]["output"][0]["content"] = [{"type": "refusal", "refusal": "PRIVATE"}]
        variants[2]["output"][0]["content"][0]["text"] = '{"is_expenditure":"true"}'
        for raw in variants:
            with patch.object(classifier, "send_request", return_value=raw):
                result = json.loads(classify_sms(payload(), "PRIVATE-KEY"))
            self.assertEqual(result["status"], "failed")
            self.assertNotIn("is_expenditure", result)
            self.assertIn("estimated_cost_usd", result["usage"])
            self.assertNotIn("PRIVATE", json.dumps(result))
        with patch.object(category, "send_request", return_value=variants[0]):
            result = json.loads(identify_sms_category(payload(), "PRIVATE-KEY"))
        self.assertEqual(result["status"], "failed")
        self.assertIn("estimated_cost_usd", result["usage"])

    def test_network_failure_has_timing_but_no_usage_or_zero_cost(self):
        for helper, entry in ((classifier, classify_sms), (category, identify_sms_category)):
            with patch.object(helper, "send_request", side_effect=classifier.ClassificationError("timeout")):
                result = json.loads(entry(payload(), "PRIVATE-KEY"))
            self.assertEqual(result["error_code"], "timeout")
            self.assertNotIn("usage", result)
            self.assertGreaterEqual(result["api_elapsed_ms"], 0)

    def test_bad_telemetry_does_not_change_a_valid_prediction(self):
        raw = response(False)
        raw["usage"]["input_tokens"] = "PRIVATE"
        with patch.object(classifier, "send_request", return_value=raw):
            result = json.loads(classify_sms(payload(), "PRIVATE-KEY"))
        self.assertIs(result["is_expenditure"], False)
        self.assertNotIn("usage", result)

    def test_telemetry_whitelist_ignores_private_fields(self):
        raw = response()
        raw["usage"]["body"] = "PRIVATE-SMS"
        raw["api_key"] = "PRIVATE-KEY"
        raw["model"] = "PRIVATE invalid model"
        result = read_usage(raw, PRICED_MODEL)
        self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertNotIn("estimated_cost_usd", result)


if __name__ == "__main__":
    unittest.main()
