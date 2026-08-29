"""Local development tracing for the expense tracker."""

from __future__ import annotations

from config import DEBUG_TRACING


def trace(event: str, **details: object) -> None:
    """Print local traces without exposing full account identifiers."""
    if not DEBUG_TRACING:
        return

    fields = " ".join(f"{key}={value!r}" for key, value in details.items())
    print(f"[expense-tracker] {event}" + (f" | {fields}" if fields else ""), flush=True)


def mask_identifier(value: str) -> str:
    """Keep enough of an identifier for correlation without printing it in full."""
    if len(value) <= 6:
        return "***"
    return f"{value[:4]}...{value[-4:]}"
