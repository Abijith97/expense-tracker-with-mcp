"""WhatsApp MCP host for recording and querying private expense data."""

from __future__ import annotations

import html
import json
import os
import re
from contextlib import asynccontextmanager
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from typing import Any

from fastapi import FastAPI, HTTPException, Request, Response
from mcp import Client
from ollama import Client as OllamaClient

import storage
from expense_mcp_server import expense_mcp


OLLAMA_HOST = os.getenv("OLLAMA_CLIENT_HOST", "http://127.0.0.1:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen3:8b")

VALID_CATEGORIES = [
    "Food",
    "Transport",
    "Groceries",
    "Family",
    "Gifts",
    "Utilities",
    "Clothing",
    "Investments",
    "Home & Kitchen",
    "Family & Leisure",
    "Family & Celebration",
    "Snacks",
    "Other",
]

# Build the child ASGI app before accessing its session manager in FastAPI's
# lifespan. The mounted endpoint is available to MCP Inspector or another
# authorized MCP client at /mcp.
mcp_asgi_app = expense_mcp.streamable_http_app()


@asynccontextmanager
async def lifespan(_: FastAPI):
    """Initialize storage and the mounted MCP server before serving requests."""
    storage.initialize_database()
    async with expense_mcp.session_manager.run():
        yield


app = FastAPI(lifespan=lifespan)
ollama_client = OllamaClient(host=OLLAMA_HOST)


def build_extraction_prompt(user_message: str) -> str:
    """Build the strict JSON-only prompt used to extract expenses from a message."""
    today = datetime.now().strftime("%Y-%m-%d")
    categories = "\n".join(f"- {category}" for category in VALID_CATEGORIES)

    return f"""
You are an expense extraction system.

Today's date is {today}.

Valid categories:
{categories}

Return ONLY valid JSON.

Schema:
{{
  "expenses": [
    {{
      "date": "YYYY-MM-DD",
      "category": "Category Name",
      "event": "Description",
      "price": 0
    }}
  ]
}}

Rules:
- Extract all expenses mentioned in the message.
- One expense entry per purchase/spend event.
- The date field is mandatory.
- Convert all dates to ISO format YYYY-MM-DD.
- If no date is mentioned, use today's date.
- Price must be numeric without currency symbols.
- Choose the most appropriate category from the valid categories list.
- Preserve important event details.
- Return JSON only.
- Do not include markdown, explanations, or additional text.

Message:
{user_message}
"""


def validate_expenses(raw_expenses: object) -> list[dict[str, object]]:
    """Validate LLM output before sending it to the Expense MCP server."""
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


async def call_expense_tool(tool_name: str, arguments: dict[str, object]) -> dict[str, Any]:
    """Invoke an Expense MCP server tool through the application's MCP client."""
    async with Client(expense_mcp) as client:
        result = await client.call_tool(tool_name, arguments)

    if result.is_error:
        message = result.content[0].text if result.content else "The expense tool could not complete the request."
        raise RuntimeError(message)
    if not isinstance(result.structured_content, dict):
        raise RuntimeError("The expense tool returned an unexpected response.")
    return result.structured_content


def twiml_message(message: str) -> Response:
    """Create a TwiML WhatsApp reply without requiring outbound API credentials."""
    escaped_message = html.escape(message)
    return Response(content=f"<Response><Message>{escaped_message}</Message></Response>", media_type="application/xml")


def help_message(help_result: dict[str, Any]) -> str:
    """Render the MCP help tool response for a WhatsApp message."""
    examples = "\n".join(f"• {example}" for example in help_result["examples"])
    return f"{help_result['message']}\n\nExamples:\n{examples}\n\nSend HELP anytime."


def summary_period(message: str) -> str | None:
    """Recognize the first POC set of natural-language summary requests."""
    normalized = message.lower()
    if not any(keyword in normalized for keyword in ("spend", "spent", "summary", "total")):
        return None
    if "year" in normalized:
        return "yearly"
    if "month" in normalized:
        return "monthly"
    if "week" in normalized:
        return "weekly"
    if "day" in normalized or "today" in normalized:
        return "daily"
    return None


def format_summary(summary: dict[str, Any]) -> str:
    """Render exact database totals returned by the summary MCP tool."""
    category_lines = [f"• {item['category']}: ₹{item['amount']}" for item in summary["categories"]]
    details = "\n".join(category_lines) if category_lines else "No expenses recorded for this period."
    return (
        f"{summary['period'].capitalize()} summary ({summary['start']} to {summary['end']})\n"
        f"Total: ₹{summary['total']}\n\n{details}"
    )


def format_expense(expense: dict[str, Any]) -> str:
    """Render one tool-returned expense record."""
    return f"#{expense['id']} — {expense['event']} | {expense['category']} | ₹{expense['amount']} on {expense['date']}"


async def handle_message(sender: str, message: str, is_first_message: bool) -> str:
    """Select an MCP tool or extract a new expense from an ordinary message."""
    normalized = message.strip().lower()
    if normalized in {"start", "help", "hi", "hello"}:
        return help_message(await call_expense_tool("get_help", {}))

    period = summary_period(message)
    if period:
        summary = await call_expense_tool(
            "get_summary",
            {"whatsapp_number": sender, "period": period},
        )
        return format_summary(summary)

    confirm_match = re.fullmatch(r"confirm\s+delete\s+(\d+)", normalized)
    delete_match = re.fullmatch(r"delete(?:\s+expense)?\s+(\d+)", normalized)
    get_match = re.fullmatch(r"(?:get|show)\s+expense\s+(\d+)", normalized)
    find_match = re.fullmatch(r"find(?:\s+expense)?\s+(.+)", normalized)

    if confirm_match:
        result = await call_expense_tool(
            "delete_expense",
            {"whatsapp_number": sender, "expense_id": int(confirm_match.group(1)), "confirmed": True},
        )
        return f"Deleted {format_expense(result['expense'])}." if result["deleted"] else result["reason"]
    if delete_match:
        result = await call_expense_tool(
            "delete_expense",
            {"whatsapp_number": sender, "expense_id": int(delete_match.group(1))},
        )
        if result.get("requires_confirmation"):
            return f"Delete {format_expense(result['expense'])}? Reply: CONFIRM DELETE {result['expense']['id']}"
        return result["reason"]
    if get_match:
        result = await call_expense_tool(
            "get_expense",
            {"whatsapp_number": sender, "expense_id": int(get_match.group(1))},
        )
        return format_expense(result["expense"]) if result["found"] else "I could not find that expense in your tracker."
    if find_match:
        result = await call_expense_tool(
            "find_expenses",
            {"whatsapp_number": sender, "text": find_match.group(1)},
        )
        expenses = result["expenses"]
        if not expenses:
            return "I could not find a matching expense in your tracker."
        return "Matching expenses:\n" + "\n".join(format_expense(expense) for expense in expenses)

    response = ollama_client.chat(
        model=OLLAMA_MODEL,
        messages=[{"role": "user", "content": build_extraction_prompt(message)}],
        format="json",
        think=False,
    )
    extracted_data: Any = json.loads(response.message.content)
    if not isinstance(extracted_data, dict):
        raise ValueError("The model response must be a JSON object.")

    expenses = validate_expenses(extracted_data.get("expenses", []))
    if not expenses:
        prefix = "Welcome! Send HELP to see what I can do.\n\n" if is_first_message else ""
        return f"{prefix}I could not find an expense in that message. Send HELP for examples."

    recorded = []
    for expense in expenses:
        result = await call_expense_tool(
            "record_expense",
            {
                "whatsapp_number": sender,
                "date": expense["date"],
                "category": expense["category"],
                "event": expense["event"],
                "amount": expense["price"],
            },
        )
        recorded.append(format_expense(result["expense"]))

    prefix = "Welcome! Your private tracker is ready. Send HELP anytime.\n\n" if is_first_message else ""
    return f"{prefix}Added:\n" + "\n".join(recorded)


@app.post("/webhook")
async def whatsapp_webhook(request: Request) -> Response:
    """Receive Twilio messages and route them through the Expense MCP client."""
    data = await request.form()
    user_message = str(data.get("Body", "")).strip()
    sender = str(data.get("From", "")).strip()
    message_sid = str(data.get("MessageSid", "")).strip()

    if not user_message or not sender:
        raise HTTPException(status_code=400, detail="Twilio webhook must include Body and From values.")

    with storage.SessionLocal() as session:
        is_first_message = storage.get_user(session, sender) is None
        user = storage.get_or_create_user(session, sender)
        webhook_event = None

        if message_sid:
            existing_event = storage.get_webhook_event(session, message_sid)
            if existing_event is not None and existing_event.status in {"processed", "processing"}:
                return twiml_message("This message has already been received.")
            webhook_event = existing_event or storage.begin_webhook_event(session, message_sid, user)

    try:
        reply = await handle_message(sender, user_message, is_first_message)
    except Exception:
        with storage.SessionLocal() as session:
            storage.mark_webhook_failed(session, storage.get_webhook_event(session, message_sid) if message_sid else None)
        raise

    with storage.SessionLocal() as session:
        storage.mark_webhook_processed(session, storage.get_webhook_event(session, message_sid) if message_sid else None)
    return twiml_message(reply)


# Mount after FastAPI's application routes so /webhook remains owned by FastAPI.
# The MCP Streamable HTTP endpoint is available at /mcp.
app.mount("/", mcp_asgi_app)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)
