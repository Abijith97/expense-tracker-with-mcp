"""Offline contract tests: mock the API, never bill a real key or upload SMS.

These verify orchestration, request format, and failure handling; they do NOT
prove an LLM's classification accuracy. Run the optional live evaluation for it.
"""

import json
from pathlib import Path
import socket
import sys
import unittest
import urllib.error
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
import expenditure_classifier as classifier
from classifier_examples import EXPENDITURE_EXAMPLES, OTHER_EXAMPLES, PROMPT_EXAMPLES
from orchestrator import classify_sms


def response_for(decision):
    return {"status": "completed", "output": [
        {"type": "reasoning", "summary": []},
        {"type": "message", "content": [{"type": "output_text", "text": json.dumps(
            {"is_expenditure": decision})}]},
    ]}


class ExpenditureClassifierTests(unittest.TestCase):
    def payload(self, body="Sent Rs.120 to Example Shop."):
        return json.dumps({"schema_version": 1, "message_id": "demo-id",
            "sender": "DEMO-PRIVATE-SENDER", "body": body, "sent_at_ms": 0, "received_at_ms": 1})

    def test_prompt_contains_sanitized_positive_and_negative_examples(self):
        self.assertEqual(len(EXPENDITURE_EXAMPLES), 25)
        self.assertEqual(len(OTHER_EXAMPLES), 9)
        labels = [label for _, label in PROMPT_EXAMPLES]
        self.assertIn(True, labels)
        self.assertIn(False, labels)
        request = classifier.build_request("DEMO-PRIVATE-BODY")
        self.assertEqual(request["input"][-1], {"role": "user", "content": "DEMO-PRIVATE-BODY"})
        self.assertIs(request["store"], False)
        schema = request["text"]["format"]
        self.assertIs(schema["strict"], True)
        self.assertEqual(schema["schema"]["properties"], {"is_expenditure": {"type": "boolean"}})
        self.assertIs(schema["schema"]["additionalProperties"], False)
        self.assertNotIn("api_key", json.dumps(request))

    def test_parser_accepts_only_actual_boolean(self):
        for decision in (True, False):
            with self.subTest(decision=decision):
                self.assertIs(classifier.parse_response(response_for(decision)), decision)
        for invalid in ("true", "false", 1, 0, None, [], {}):
            with self.subTest(invalid=invalid):
                with self.assertRaises(classifier.ClassificationError):
                    classifier.parse_response(response_for(invalid))

    def test_parser_rejects_bad_shapes_extra_fields_and_multiple_outputs(self):
        bad = [None, [], {}, {"status": "completed", "output": None},
               {"status": "completed", "output": [{}]},
               {"status": "completed", "output": [{"type": "message", "content": None}]}]
        for text in ('{"is_expenditure":true,"extra":"private"}', 'true', '{"private":"broken"', '{}'):
            response = response_for(True)
            response["output"][1]["content"][0]["text"] = text
            bad.append(response)
        duplicate = response_for(True)
        duplicate["output"].append(duplicate["output"][1])
        bad.append(duplicate)
        for response in bad:
            with self.subTest(response=response):
                with self.assertRaises(classifier.ClassificationError) as caught:
                    classifier.parse_response(response)
                self.assertNotIn("private", str(caught.exception))

    def test_refusal_and_incomplete_are_errors_not_other(self):
        refusal = {"status": "completed", "output": [{"type": "message", "content": [
            {"type": "refusal", "refusal": "PRIVATE-RESPONSE"}]}]}
        for response, code in ((refusal, "refused"),
            ({"status": "incomplete", "output": []}, "incomplete_response")):
            with self.subTest(code=code):
                with self.assertRaises(classifier.ClassificationError) as caught:
                    classifier.parse_response(response)
                self.assertEqual(caught.exception.code, code)

    def test_invalid_input_and_key_make_no_network_request(self):
        with patch.object(classifier.urllib.request, "build_opener") as opener:
            for body in ("", " ", None, 1, "x" * (classifier.MAX_BODY_CHARS + 1)):
                with self.subTest(body_type=type(body)):
                    with self.assertRaises(classifier.ClassificationError):
                        classifier.classify_expenditure(body, "DEMO-KEY")
            for key in ("", None, "DEMO\nKEY", "DEMO KEY"):
                with self.subTest(key_type=type(key)):
                    with self.assertRaises(classifier.ClassificationError):
                        classifier.classify_expenditure("Demo SMS", key)
            opener.assert_not_called()

    def test_success_makes_one_https_request_with_safe_settings(self):
        with patch.object(classifier.urllib.request, "build_opener") as factory:
            factory.return_value.open.return_value.__enter__.return_value.read.return_value = json.dumps(response_for(True)).encode()
            decision, elapsed = classifier.classify_expenditure("DEMO-BODY", "DEMO-KEY")
            self.assertIs(decision, True)
            self.assertGreaterEqual(elapsed, 0)
            factory.return_value.open.assert_called_once()
            request = factory.return_value.open.call_args.args[0]
            self.assertEqual(request.full_url, "https://api.openai.com/v1/responses")
            self.assertEqual(request.get_header("Authorization"), "Bearer DEMO-KEY")
            self.assertEqual(factory.return_value.open.call_args.kwargs["timeout"], 20)
            self.assertNotIn("DEMO-KEY", request.data.decode())

    def test_http_and_network_errors_are_safe_without_retries(self):
        cases = [(urllib.error.HTTPError("demo", code, "PRIVATE-DETAIL", {}, None), expected)
                 for code, expected in ((401, "authentication"), (403, "access_denied"),
                     (429, "rate_limit_or_quota"), (500, "service_unavailable"), (400, "request_rejected"))]
        cases.extend([(socket.timeout("PRIVATE-DETAIL"), "timeout"),
            (urllib.error.URLError("PRIVATE-DETAIL"), "network"),
            (urllib.error.URLError(socket.timeout()), "timeout")])
        for error, expected in cases:
            with self.subTest(expected=expected), patch.object(classifier.urllib.request, "build_opener") as factory:
                factory.return_value.open.side_effect = error
                with self.assertRaises(classifier.ClassificationError) as caught:
                    classifier.classify_expenditure("DEMO-BODY", "DEMO-KEY")
                self.assertEqual(str(caught.exception), expected)
                factory.return_value.open.assert_called_once()

    def test_malformed_or_oversized_http_response_is_safe(self):
        for raw in (b"PRIVATE-BROKEN-JSON", b"x" * (classifier.MAX_RESPONSE_BYTES + 1)):
            with self.subTest(size=len(raw)), patch.object(classifier.urllib.request, "build_opener") as factory:
                factory.return_value.open.return_value.__enter__.return_value.read.return_value = raw
                with self.assertRaises(classifier.ClassificationError) as caught:
                    classifier.classify_expenditure("DEMO-BODY", "DEMO-KEY")
                self.assertEqual(caught.exception.code, "invalid_response")

    def test_sms_orchestration_preserves_boolean_and_omits_private_data(self):
        for decision in (True, False):
            with self.subTest(decision=decision), patch("orchestrator.classify_expenditure", return_value=(decision, 12.5)) as call:
                result = json.loads(classify_sms(self.payload(), "DEMO-KEY"))
                self.assertEqual(result["status"], "classified")
                self.assertIs(result["is_expenditure"], decision)
                self.assertEqual(result["message_id"], "demo-id")
                self.assertEqual(result["api_elapsed_ms"], 12.5)
                call.assert_called_once_with("Sent Rs.120 to Example Shop.", "DEMO-KEY", classifier.DEFAULT_MODEL)
                self.assertNotIn("DEMO-PRIVATE-SENDER", json.dumps(result))
                self.assertNotIn("DEMO-KEY", json.dumps(result))
                self.assertNotIn("Sent Rs", json.dumps(result))

    def test_sms_orchestration_failure_has_no_false_decision(self):
        with patch("orchestrator.classify_expenditure", side_effect=classifier.ClassificationError("authentication")):
            result = json.loads(classify_sms(self.payload(), "DEMO-KEY"))
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["error_code"], "authentication")
        self.assertNotIn("is_expenditure", result)

    def test_invalid_sms_payload_does_not_invoke_classifier(self):
        with patch("orchestrator.classify_expenditure") as call:
            with self.assertRaises(ValueError):
                classify_sms('{"body":"PRIVATE"}', "DEMO-KEY")
            call.assert_not_called()

    def test_direct_classification_rejects_invalid_decision_or_timing(self):
        # Guard the direct-call boundary as well as the raw provider parser.
        invalid_results = [("true", 1), (1, 1), (None, 1),
                           (True, True), (True, "1"), (True, -1),
                           (False, float("nan")), (False, float("inf"))]
        for output in invalid_results:
            with self.subTest(output=output), patch("orchestrator.classify_expenditure", return_value=output):
                result = json.loads(classify_sms(self.payload(), "DEMO-KEY"))
                self.assertEqual(result, {"schema_version": 1, "message_id": "demo-id",
                                         "status": "failed", "error_code": "invalid_response"})

    def test_redirects_cannot_forward_body_or_key(self):
        handler = classifier._NoRedirect()
        self.assertIsNone(handler.redirect_request(None, None, 302, "demo", {}, "https://example.invalid"))

    def test_sanitized_corpus_flow_with_stub_is_not_a_model_accuracy_test(self):
        # All 34 user-derived cases exercise the production request and parser.
        # The stub supplies the known label; this does NOT assess semantic accuracy.
        for body, label in tuple((text, True) for text in EXPENDITURE_EXAMPLES) + tuple((text, False) for text in OTHER_EXAMPLES):
            with self.subTest(label=label), patch.object(classifier.urllib.request, "build_opener") as factory:
                factory.return_value.open.return_value.__enter__.return_value.read.return_value = json.dumps(response_for(label)).encode()
                result = json.loads(classify_sms(self.payload(body), "DEMO-KEY"))
                self.assertIs(result["is_expenditure"], label)
                uploaded = json.loads(factory.return_value.open.call_args.args[0].data)
                self.assertEqual(uploaded["input"][-1]["content"], body)


if __name__ == "__main__":
    unittest.main()
