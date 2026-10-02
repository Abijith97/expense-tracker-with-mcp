"""Receive SMS and chat messages from the Android application.

This is the lightweight entry point for the future phone-side orchestrator.
It currently validates the handoff only; it does not run an AI agent, decide
whether a message is financial, or record an expense. Chat returns built-in
capability information until an LLM and phone-side MCP tools are integrated.
"""

from __future__ import annotations

import json
import platform
from datetime import datetime, timezone
from typing import Any


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


def handle_chat(payload_json: str) -> str:
    """Return local help through the same embedded Python runtime as SMS capture.

    Every valid message currently receives capability information. This proves
    the chat handoff without pretending to interpret spending queries. It uses
    no LLM, MCP server, network, or finance database, and does not echo the input.
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
        "reply": (
            "Your message reached the phone-side Python orchestrator.\n\n"
            "This build provides built-in help and captures new SMS after you "
            "enable access in the SMS capture tab. Captured messages get a "
            "Python acknowledgement.\n\n"
            "Expense classification, category confirmation, recording expenses, "
            "and spending summaries are planned. They are not available yet. "
            "For now, every chat message returns this help information."
        ),
        "processed_at_utc": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
    })
