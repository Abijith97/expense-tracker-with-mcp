"""Validation and authorization boundary for LLM-selected MCP tools."""

from __future__ import annotations

import re
from datetime import date
from decimal import Decimal, InvalidOperation
from typing import Any

from config import VALID_CATEGORIES


def validate_expenses(raw_expenses: object) -> list[dict[str, object]]:
    """Validate model-proposed expense fields before any MCP call is made."""
    if not isinstance(raw_expenses, list):
        raise ValueError("The model response contains an invalid expenses field.")

    validated_expenses: list[dict[str, object]] = []
    for raw_expense in raw_expenses:
        if not isinstance(raw_expense, dict):
            raise ValueError("Every expense must be a JSON object.")

        try:
            occurred_on = date.fromisoformat(str(raw_expense["date"]))
            category = str(raw_expense["category"])
            event = str(raw_expense["event"]).strip()
            amount = Decimal(str(raw_expense["price"])).quantize(Decimal("0.01"))
        except (KeyError, TypeError, ValueError, InvalidOperation) as error:
            raise ValueError("The model returned an incomplete or invalid expense.") from error

        if category not in VALID_CATEGORIES:
            raise ValueError(f"Unsupported expense category: {category}")
        if not event:
            raise ValueError("Expense descriptions cannot be empty.")
        if amount <= 0:
            raise ValueError("Expense amounts must be greater than zero.")

        validated_expenses.append(
            {
                "date": occurred_on.isoformat(),
                "category": category,
                "event": event,
                "price": str(amount),
            }
        )

    return validated_expenses


def _has_explicit_delete_confirmation(user_message: str, expense_id: int) -> bool:
    """Require a natural-language confirmation that names the expense to delete."""
    normalized = user_message.lower()
    confirms_deletion = bool(re.search(r"\b(yes|confirm|confirmed)\b.*\bdelete\b|\bdelete\b.*\b(yes|confirm)", normalized))
    return confirms_deletion and str(expense_id) in normalized


def validated_tool_arguments(
    tool_name: str,
    raw_arguments: dict[str, Any],
    sender: str,
    user_message: str,
) -> dict[str, object]:
    """Validate one LLM tool choice and inject the authenticated user identity."""
    if tool_name == "get_help":
        return {}
    if tool_name == "record_expense":
        expense = validate_expenses(
            [
                {
                    "date": raw_arguments.get("date"),
                    "category": raw_arguments.get("category"),
                    "event": raw_arguments.get("event"),
                    "price": raw_arguments.get("amount"),
                }
            ]
        )[0]
        return {
            "whatsapp_number": sender,
            "date": expense["date"],
            "category": expense["category"],
            "event": expense["event"],
            "amount": expense["price"],
        }
    if tool_name == "get_summary":
        period = str(raw_arguments.get("period", ""))
        if period not in {"daily", "weekly", "monthly", "yearly"}:
            raise ValueError("The requested summary period is invalid.")
        reference_date = raw_arguments.get("reference_date")
        if reference_date is not None:
            reference_date = date.fromisoformat(str(reference_date)).isoformat()
        return {"whatsapp_number": sender, "period": period, "reference_date": reference_date}
    if tool_name == "find_expenses":
        arguments: dict[str, object] = {"whatsapp_number": sender}
        if raw_arguments.get("date"):
            arguments["date"] = date.fromisoformat(str(raw_arguments["date"])).isoformat()
        if raw_arguments.get("category"):
            category = str(raw_arguments["category"])
            if category not in VALID_CATEGORIES:
                raise ValueError("The requested category is invalid.")
            arguments["category"] = category
        if raw_arguments.get("text"):
            arguments["text"] = str(raw_arguments["text"]).strip()
        if len(arguments) == 1:
            raise ValueError("A search needs a date, category, or description.")
        return arguments
    if tool_name in {"get_expense", "delete_expense"}:
        try:
            expense_id = int(raw_arguments["expense_id"])
        except (KeyError, TypeError, ValueError) as error:
            raise ValueError("An expense ID is required.") from error
        arguments = {"whatsapp_number": sender, "expense_id": expense_id}
        if tool_name == "delete_expense":
            # The model's JSON alone is never enough to confirm a deletion.
            arguments["confirmed"] = bool(raw_arguments.get("confirmed")) and _has_explicit_delete_confirmation(
                user_message,
                expense_id,
            )
        return arguments
    raise ValueError("The agent selected an unsupported tool.")
