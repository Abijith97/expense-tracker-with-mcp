"""Test-APK-only HTTP fake. Production prompts, transport and parsers stay real.

Never packaged in the demo APK. Only synthetic keys and SMS are accepted.
"""

import io
import json
import urllib.error
from unittest.mock import patch

SCENARIOS = {"happy", "other", "timeout", "retry", "malformed", "category_failure", "uncertain"}
CLASSIFICATION_KEY = "SYSTEM-TEST-CLASSIFICATION"
CATEGORY_KEY = "SYSTEM-TEST-CATEGORY"
_patches = []
_calls = []
_scenario = None


def install(scenario):
    global _scenario
    if scenario not in SCENARIOS:
        raise ValueError("Unknown synthetic scenario")
    uninstall()
    _scenario = scenario
    _calls.clear()
    # Patch below send_request, so its request/error handling is exercised too.
    _patches.extend([
        patch("urllib.request.build_opener", return_value=_Opener()),
        patch("urllib.request.urlopen", side_effect=AssertionError("Live HTTP forbidden")),
    ])
    for replacement in _patches:
        replacement.start()


def uninstall():
    for replacement in reversed(_patches):
        replacement.stop()
    _patches.clear()


def calls_json():
    return json.dumps(_calls)


class _Opener:
    def open(self, request, timeout):
        from llm_api import ENDPOINT, REQUEST_TIMEOUT_SECONDS

        assert request.full_url == ENDPOINT
        assert request.get_method() == "POST"
        assert timeout == REQUEST_TIMEOUT_SECONDS
        payload = json.loads(request.data)
        schema = payload["text"]["format"]
        stage = schema["name"]
        assert stage in {"expenditure_classification", "spending_category"}
        key = CLASSIFICATION_KEY if stage == "expenditure_classification" else CATEGORY_KEY
        assert request.get_header("Authorization") == "Bearer " + key
        assert schema["strict"] is True and payload["store"] is False
        assert len(payload["input"]) > 2  # Real few-shot prompt, not a stubbed decision.
        body = payload["input"][-1]["content"]
        assert body.endswith(" Test:" + _scenario + ".")
        _calls.append(stage)
        if stage == "expenditure_classification":
            if _scenario == "timeout" or (_scenario == "retry" and len(_calls) == 1):
                raise TimeoutError("synthetic timeout")
            decision = {"is_expenditure": _scenario != "other"}
            if _scenario == "malformed":
                decision = {"is_expenditure": "true"}  # Must not become truthy success.
        else:
            if _scenario == "category_failure":
                raise urllib.error.HTTPError(request.full_url, 401, "synthetic rejection", {}, io.BytesIO())
            label = "Not sure" if _scenario == "uncertain" else "utilities"
            decision = {"category": label, "sub_category": "Not sure" if label == "Not sure" else "transport"}
        return io.BytesIO(json.dumps({
            "status": "completed",
            "output": [{"type": "message", "content": [{"type": "output_text", "text": json.dumps(decision)}]}],
            "usage": {"input_tokens": 100, "output_tokens": 10,
                      "input_tokens_details": {"cached_tokens": 0}},
        }).encode("utf-8"))
