"""WhatsApp response formatting and TwiML creation."""

from __future__ import annotations

import html
from typing import Any

from fastapi import Response


def twiml_message(message: str) -> Response:
    """Create a TwiML WhatsApp reply without outbound API credentials."""
    escaped_message = html.escape(message)
    content = f'<?xml version="1.0" encoding="UTF-8"?><Response><Message>{escaped_message}</Message></Response>'
    return Response(content=content, media_type="text/xml")


def format_expense(expense: dict[str, Any]) -> str:
    """Render one tool-returned expense record."""
    return f"#{expense['id']} — {expense['event']} | {expense['category']} | ₹{expense['amount']} on {expense['date']}"


def help_message(help_result: dict[str, Any]) -> str:
    """Render the MCP help tool response for a WhatsApp message."""
    examples = "\n".join(f"• {example}" for example in help_result["examples"])
    return f"{help_result['message']}\n\nExamples:\n{examples}\n\nSend HELP anytime."


def format_summary(summary: dict[str, Any]) -> str:
    """Render exact database totals returned by the summary MCP tool."""
    category_lines = [f"• {item['category']}: ₹{item['amount']}" for item in summary["categories"]]
    details = "\n".join(category_lines) if category_lines else "No expenses recorded for this period."
    return (
        f"{summary['period'].capitalize()} summary ({summary['start']} to {summary['end']})\n"
        f"Total: ₹{summary['total']}\n\n{details}"
    )


def render_tool_result(tool_name: str, result: dict[str, Any]) -> str:
    """Convert a trusted MCP result into a user-facing reply."""
    if tool_name == "get_help":
        return help_message(result)
    if tool_name == "record_expense":
        return f"Added {format_expense(result['expense'])}."
    if tool_name == "get_summary":
        return format_summary(result)
    if tool_name == "find_expenses":
        expenses = result["expenses"]
        return "Matching expenses:\n" + "\n".join(format_expense(expense) for expense in expenses) if expenses else "I could not find a matching expense."
    if tool_name == "get_expense":
        return format_expense(result["expense"]) if result["found"] else "I could not find that expense in your tracker."
    return "The request completed."
