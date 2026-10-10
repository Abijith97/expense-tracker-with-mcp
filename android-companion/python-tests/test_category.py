"""Offline category contracts. Stubbed labels do not establish model accuracy."""

import json
from pathlib import Path
import socket
import sys
import unittest
import urllib.error
from unittest.mock import ANY, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
import category_identifier as identifier
import llm_api
from category_examples import (CATEGORIES, SUB_CATEGORIES, CATEGORY_PAIRS, CATEGORY_HIERARCHY,
    PERSONAL_EXAMPLES, PROMPT_EXAMPLES, is_valid_label)
from orchestrator import identify_sms_category


def label(category="utilities", sub_category="groceries"):
    return {"category": category, "sub_category": sub_category}


def response_for(category="utilities", sub_category="groceries"):
    return {"status": "completed", "output": [{"type": "message", "content": [
        {"type": "output_text", "text": json.dumps(label(category, sub_category))}]}]}


class CategoryIdentificationTests(unittest.TestCase):
    def payload(self):
        return json.dumps({"schema_version": 1, "message_id": "demo-category",
            "sender": "DEMO-PRIVATE-SENDER", "body": "DEMO-PRIVATE-BODY",
            "sent_at_ms": 0, "received_at_ms": 1})

    def test_request_has_strict_enum_and_synthetic_few_shot_examples(self):
        request = identifier.build_request("DEMO-PRIVATE-BODY")
        self.assertEqual(request["input"][-1], {"role": "user", "content": "DEMO-PRIVATE-BODY"})
        self.assertIs(request["store"], False)
        schema = request["text"]["format"]
        self.assertIs(schema["strict"], True)
        self.assertEqual(schema["schema"]["properties"], {
            "category": {"type": "string", "enum": list(CATEGORIES)},
            "sub_category": {"type": "string", "enum": list(SUB_CATEGORIES)}})
        self.assertEqual(schema["schema"]["required"], ["category", "sub_category"])
        self.assertIs(schema["schema"]["additionalProperties"], False)
        self.assertEqual({(category, sub) for _, category, sub in PROMPT_EXAMPLES}, set(CATEGORY_PAIRS))
        self.assertEqual(len(request["input"]), len(PROMPT_EXAMPLES) * 2 + 1)
        for i, (_, category, sub) in enumerate(PROMPT_EXAMPLES):
            self.assertEqual(json.loads(request["input"][i * 2 + 1]["content"]), label(category, sub))

    def test_model_override_does_not_receive_default_model_reasoning_setting(self):
        self.assertEqual(identifier.build_request("Demo")["reasoning"], {"effort": "none"})
        override = identifier.build_request("Demo", " demo-model ")
        self.assertEqual(override["model"], "demo-model")
        self.assertNotIn("reasoning", override)

    def test_prompt_describes_uncertainty_and_untrusted_input(self):
        self.assertIn("Not sure", identifier.INSTRUCTIONS)
        self.assertIn("person's name", identifier.INSTRUCTIONS)
        self.assertIn("general marketplace", identifier.INSTRUCTIONS)
        self.assertIn("untrusted data", identifier.INSTRUCTIONS)
        self.assertIn("Prefer an explicit payment purpose", identifier.INSTRUCTIONS)

    def test_parser_preserves_every_allowed_category_including_not_sure(self):
        for category, sub in CATEGORY_PAIRS:
            with self.subTest(category=category, sub_category=sub):
                self.assertEqual(identifier.parse_response(response_for(category, sub)), label(category, sub))

    def test_parser_rejects_unknown_labels_types_extra_fields_and_raw_text(self):
        bad = [response_for(label) for label in (None, True, 1, [], {}, "groceries", "PRIVATE-LABEL", "")]
        for text in ('utilities', '"utilities"', '{"category":"utilities","sub_category":"groceries","body":"PRIVATE"}',
                     '{"category":"utilities"}', '{}'):
            response = response_for()
            response["output"][0]["content"][0]["text"] = text
            bad.append(response)
        for response in bad:
            with self.subTest(response=response), self.assertRaises(identifier.CategoryError) as caught:
                identifier.parse_response(response)
            self.assertEqual(caught.exception.code, "invalid_response")
            self.assertNotIn("PRIVATE", str(caught.exception))

    def test_refused_incomplete_and_multiple_outputs_are_safe_failures(self):
        refusal = {"status": "completed", "output": [{"type": "message", "content": [
            {"type": "refusal", "refusal": "PRIVATE-DETAIL"}]}]}
        duplicate = response_for()
        duplicate["output"].append(duplicate["output"][0])
        for response, code in ((refusal, "refused"), ({"status": "incomplete"}, "incomplete_response"),
                               (duplicate, "invalid_response")):
            with self.subTest(code=code), self.assertRaises(identifier.CategoryError) as caught:
                identifier.parse_response(response)
            self.assertEqual(caught.exception.code, code)

    def test_invalid_input_model_and_missing_key_never_make_http_requests(self):
        with patch.object(llm_api.urllib.request, "build_opener") as factory:
            for body in ("", " ", None, 1, "x" * (identifier.MAX_BODY_CHARS + 1)):
                with self.subTest(body_type=type(body)), self.assertRaises(identifier.CategoryError):
                    identifier.identify_category(body, "DEMO-KEY")
            for model in ("", None, "x" * 101):
                with self.subTest(model=model), self.assertRaises(identifier.CategoryError):
                    identifier.identify_category("Demo", "DEMO-KEY", model)
            for key in ("", None, "DEMO\nKEY"):
                with self.subTest(key=key), self.assertRaises(identifier.CategoryError) as caught:
                    identifier.identify_category("Demo", key)
                self.assertEqual(caught.exception.code, "configuration")
            factory.assert_not_called()

    def test_category_makes_one_https_call_with_shared_safety_settings(self):
        with patch.object(llm_api.urllib.request, "build_opener") as factory:
            factory.return_value.open.return_value.__enter__.return_value.read.return_value = json.dumps(response_for()).encode()
            category, elapsed = identifier.identify_category("DEMO-PRIVATE-BODY", "DEMO-KEY")
            self.assertEqual(category, label())
            self.assertGreaterEqual(elapsed, 0)
            factory.return_value.open.assert_called_once()
            request = factory.return_value.open.call_args.args[0]
            self.assertEqual(request.full_url, llm_api.ENDPOINT)
            self.assertEqual(request.get_header("Authorization"), "Bearer DEMO-KEY")
            self.assertEqual(factory.return_value.open.call_args.kwargs["timeout"], 60)
            uploaded = json.loads(request.data)
            self.assertIs(uploaded["store"], False)
            self.assertEqual(uploaded["input"][-1]["content"], "DEMO-PRIVATE-BODY")
            self.assertNotIn("DEMO-KEY", request.data.decode())

    def test_category_http_and_timeout_errors_are_not_not_sure(self):
        for error, expected in ((urllib.error.HTTPError("demo", 429, "PRIVATE", {}, None), "rate_limit_or_quota"),
                                (socket.timeout("PRIVATE"), "timeout")):
            with self.subTest(expected=expected), patch.object(llm_api.urllib.request, "build_opener") as factory:
                factory.return_value.open.side_effect = error
                with self.assertRaises(identifier.CategoryError) as caught:
                    identifier.identify_category("Demo", "DEMO-KEY")
                self.assertEqual(caught.exception.code, expected)
                factory.return_value.open.assert_called_once()

    def test_bad_or_oversized_provider_response_is_not_a_category(self):
        for raw in (b"PRIVATE-BROKEN-JSON", b"x" * (llm_api.MAX_RESPONSE_BYTES + 1)):
            with self.subTest(size=len(raw)), patch.object(llm_api.urllib.request, "build_opener") as factory:
                factory.return_value.open.return_value.__enter__.return_value.read.return_value = raw
                with self.assertRaises(identifier.CategoryError) as caught:
                    identifier.identify_category("Demo", "DEMO-KEY")
                self.assertEqual(caught.exception.code, "invalid_response")

    def test_orchestrator_correlates_category_without_echoing_private_data(self):
        for category, sub in CATEGORY_PAIRS:
            with self.subTest(category=category, sub_category=sub), patch("orchestrator.identify_category", return_value=(label(category, sub), 2.5)) as call:
                result = json.loads(identify_sms_category(self.payload(), "DEMO-KEY"))
                self.assertEqual(result, {"schema_version": 1, "message_id": "demo-category",
                    "status": "identified", "category": category, "sub_category": sub, "api_elapsed_ms": 2.5})
                call.assert_called_once_with("DEMO-PRIVATE-BODY", "DEMO-KEY", identifier.DEFAULT_MODEL, metrics=ANY)
                self.assertNotIn("PRIVATE", json.dumps(result))
                self.assertNotIn("DEMO-KEY", json.dumps(result))

    def test_orchestrator_failure_has_no_category_including_not_sure(self):
        with patch("orchestrator.identify_category", side_effect=identifier.CategoryError("authentication")):
            result = json.loads(identify_sms_category(self.payload(), "DEMO-KEY"))
        self.assertEqual(result, {"schema_version": 1, "message_id": "demo-category",
            "status": "failed", "error_code": "authentication"})

    def test_invalid_sms_payload_never_invokes_category_identifier(self):
        with patch("orchestrator.identify_category") as call:
            with self.assertRaises(ValueError):
                identify_sms_category('{"body":"PRIVATE"}', "DEMO-KEY")
            call.assert_not_called()

    def test_orchestrator_rejects_bad_direct_category_or_timing(self):
        for output in (("PRIVATE-LABEL", 1), (True, 1), (label(), -1),
                       (label(), True), (label(), float("inf")), (label(), float("nan")),
                       (label("learning", "transport"), 1), ({"category": "utilities"}, 1)):
            with self.subTest(output=output), patch("orchestrator.identify_category", return_value=output):
                result = json.loads(identify_sms_category(self.payload(), "DEMO-KEY"))
                self.assertEqual(result["status"], "failed")
                self.assertEqual(result["error_code"], "invalid_response")
                self.assertNotIn("category", result)


class PersonalCategoryTests(unittest.TestCase):
    def test_all_twenty_four_user_labels_are_preserved_without_bank_identifiers(self):
        expected = [
            ("learning", "engineering"), ("utilities", "transport"), ("fun", "gift"),
            ("utilities", "transport"), ("utilities", "food"), ("learning", "engineering"),
            ("utilities", "transport"), ("utilities", "prepaid recharge"), ("utilities", "transport"),
            ("utilities", "groceries"), ("utilities", "transport"), ("utilities", "food"),
            ("utilities", "transport"), ("utilities", "food"), ("fun", "food"),
            ("utilities", "groceries"), ("utilities", "transport"), ("utilities", "groceries"),
            ("utilities", "transport"), ("utilities", "groceries"), ("utilities", "transport"),
            ("utilities", "transport"), ("utilities", "groceries"), ("utilities", "health"),
        ]
        self.assertEqual([(category, sub) for _, category, sub in PERSONAL_EXAMPLES], expected)
        self.assertEqual(len(PERSONAL_EXAMPLES), 24)
        for body, category, sub in PROMPT_EXAMPLES:
            self.assertTrue(is_valid_label(label(category, sub)))
            for private in ("7382", "2858", "7308080808", "18002586161", "2026-10-03", "130201114136"):
                self.assertNotIn(private, body)
        self.assertEqual(CATEGORY_HIERARCHY["utilities"],
            ("transport", "food", "prepaid recharge", "groceries", "health"))

    def test_all_cross_category_combinations_reject_invalid_pairs_in_parser_and_orchestrator(self):
        for category in CATEGORIES:
            for sub in SUB_CATEGORIES:
                if (category, sub) in CATEGORY_PAIRS:
                    continue
                with self.subTest(category=category, sub_category=sub):
                    with self.assertRaises(identifier.CategoryError) as caught:
                        identifier.parse_response(response_for(category, sub))
                    self.assertEqual(caught.exception.code, "invalid_response")
                    with patch("orchestrator.identify_category", return_value=(label(category, sub), 1)):
                        result = json.loads(identify_sms_category(
                            CategoryIdentificationTests().payload(), "DEMO-KEY"))
                    self.assertEqual(result["status"], "failed")
                    self.assertNotIn("category", result)
                    self.assertNotIn("sub_category", result)

    def test_preferences_distinguish_food_and_known_payees_but_keep_uncertainty_and_purpose_override(self):
        self.assertIn("SWIGGY PVT LTD FOOD1 -> fun/food", identifier.INSTRUCTIONS)
        self.assertIn("Indiqube SpacesExternal S and Chethan B S -> utilities/food", identifier.INSTRUCTIONS)
        self.assertIn("ALEEM PASHA, Javed Khan, AJAS, CHANDRASHEKAR, SIVAMMA", identifier.INSTRUCTIONS)
        self.assertIn("unknown person's name", identifier.INSTRUCTIONS)
        self.assertIn("explicit different purpose overrides", identifier.INSTRUCTIONS)
        self.assertEqual(CATEGORY_HIERARCHY["Not sure"], ("Not sure",))
        examples = {body: label(category, sub) for body, category, sub in PROMPT_EXAMPLES}
        self.assertEqual(examples["Spent Rs.100 at BLINKIT for medicines. Ref DEMO"],
                         label("utilities", "health"))


if __name__ == "__main__":
    unittest.main()
