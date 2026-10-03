"""Receive SMS and chat messages from the Android application.

SMS capture stays local and fast. A separate background entry point calls the
hosted classifier directly after explicit cloud opt-in. Chat returns local help.
The orchestrator controls a fixed workflow, not an autonomous LLM tool loop.
Category extraction and finance storage come later; MCP is deferred to chatbot work.
"""

from __future__ import annotations

import json
import math
import platform
from datetime import datetime, timezone
from typing import Any
from app_help import get_help
from expenditure_classifier import ClassificationError, DEFAULT_MODEL, classify_expenditure


def _is_nonnegative_int(value: Any) -> bool:
    """Accept integer timestamps, excluding Python's boolean integer subtype."""
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def handle_sms(payload_json: str) -> str:
    """Validate a version-1 SMS payload and return a JSON receipt to Kotlin.

    The receipt identifies the message without echoing its sender or body.
    Invalid input raises a generic ValueError without exposing message content.
    This function performs no logging, persistence, network access, or inference.
    """
    invalid_payload = "Invalid SMS handoff payload."

    if not isinstance(payload_json, str):
        raise ValueError(invalid_payload)

    try:
        payload = json.loads(payload_json)
    except (ValueError, RecursionError):
        # Do not propagate parser details into Android's error reporting.
        raise ValueError(invalid_payload) from None

    if not isinstance(payload, dict):
        raise ValueError(invalid_payload)

    schema_version = payload.get("schema_version")
    message_id = payload.get("message_id")
    sender = payload.get("sender")
    body = payload.get("body")

    if (
        type(schema_version) is not int
        or schema_version != 1
        or not isinstance(message_id, str)
        or not message_id.strip()
        or not isinstance(sender, str)
        or not isinstance(body, str)
        or not body.strip()
        or not _is_nonnegative_int(payload.get("sent_at_ms"))
        or not _is_nonnegative_int(payload.get("received_at_ms"))
    ):
        raise ValueError(invalid_payload)

    # "received" confirms the handoff, not a financial classification.
    receipt = {
        "schema_version": 1,
        "received": True,
        "message_id": message_id,
        "character_count": len(body),
        "python_version": platform.python_version(),
        "processed_at_utc": datetime.now(timezone.utc).isoformat(
            timespec="milliseconds"
        ),
    }
    return json.dumps(receipt)


def classify_sms(payload_json: str, api_key: str, model: str = DEFAULT_MODEL) -> str:
    """Background-only cloud path. Failure is distinct from an 'other' decision.

    Validate the same contract as capture; only the body is passed to the API.
    The API key is a separate in-memory argument, never part of the SMS payload.
    """
    receipt = json.loads(handle_sms(payload_json))
    payload = json.loads(payload_json)
    result = {"schema_version": 1, "message_id": receipt["message_id"]}
    try:
        decision, elapsed = classify_expenditure(payload["body"], api_key, model)
        # Preserve the Android output contract without a tool-protocol layer.
        # Truthy strings and invalid timing values must never become decisions.
        if (type(decision) is not bool or type(elapsed) not in (int, float)
                or not math.isfinite(elapsed) or elapsed < 0):
            raise ClassificationError("invalid_response")
        result.update(status="classified", is_expenditure=decision, api_elapsed_ms=elapsed)
    except ClassificationError as error:
        result.update(status="failed", error_code=error.code)
    return json.dumps(result)


def handle_chat(payload_json: str) -> str:
    """Return local help through the same embedded Python runtime as SMS capture.

    Every valid message currently receives capability information. This proves
    the chat handoff without pretending to interpret spending queries. It uses
    a direct local help function, but no LLM, network, or finance database. It does
    not echo the input or send the chat body to a hosted model.
    """
    invalid_payload = "Invalid chat handoff payload."
    if not isinstance(payload_json, str):
        raise ValueError(invalid_payload)
    try:
        payload = json.loads(payload_json)
    except (ValueError, RecursionError):
        raise ValueError(invalid_payload) from None
    if not isinstance(payload, dict):
        raise ValueError(invalid_payload)
    message_id = payload.get("message_id")
    body = payload.get("body")
    if (
        type(payload.get("schema_version")) is not int
        or payload["schema_version"] != 1
        or not isinstance(message_id, str)
        or not message_id.strip()
        or not isinstance(body, str)
        or not body.strip()
    ):
        raise ValueError(invalid_payload)

    return json.dumps({
        "schema_version": 1,
        "message_id": message_id,
        "status": "capabilities",
        "reply": get_help(),
        "processed_at_utc": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
    })
