"""Implemented phone capabilities, independent of the MCP wire protocol.

One classification tool owns prompt construction, the hosted call, and boolean
validation. Internal helpers are not separate tools. Future category/database
tools belong here only after their behavior exists.
"""

import math

from expenditure_classifier import ClassificationError, DEFAULT_MODEL, classify_expenditure


def get_help():
    """Local capability information; no credentials, inference, or expense writes."""
    return {"reply": (
        "Your message reached the phone-side Python orchestrator.\n\n"
        "This build provides built-in help through the get_help MCP tool and "
        "captures new SMS after you enable access in the SMS capture tab. "
        "Captured messages get a Python acknowledgement. Enable cloud "
        "classification in SMS capture with your own OpenAI API key and consent "
        "to classify new messages as Expenditure or Other. The "
        "classify_financial_message MCP tool builds the prompt and calls OpenAI. "
        "Classification uploads the SMS body and may incur API charges.\n\n"
        "Category confirmation, recording expenses, and spending summaries are "
        "planned. They are not available yet. For now, every chat message "
        "returns this help information."
    )}


def classify_financial_message(body, api_key, model=DEFAULT_MODEL):
    """Return a validated boolean and API timing, never echoing private input."""
    decision, elapsed = classify_expenditure(body, api_key, model)
    if (type(decision) is not bool or type(elapsed) not in (int, float)
            or not math.isfinite(elapsed) or elapsed < 0):
        raise ClassificationError("invalid_response")
    return {"is_expenditure": decision, "api_elapsed_ms": elapsed}
