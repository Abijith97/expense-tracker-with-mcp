"""WhatsApp webhook that extracts expenses with Ollama and stores them by user."""

from __future__ import annotations

import json
import os
from contextlib import asynccontextmanager
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from ollama import Client

import storage


# Use a client-specific setting so Ollama's server-side `OLLAMA_HOST` setting
# does not accidentally point this application at its bind address (for
# example, `0.0.0.0:11434`, which is not a valid destination to connect to).
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


# Runs once when the app starts up, before it accepts any requests.
@asynccontextmanager
async def lifespan(_: FastAPI):
    """Initialize the local database before the application accepts requests."""
    storage.initialize_database()
    yield  # App is now ready to handle requests


app = FastAPI(lifespan=lifespan)
ollama_client = Client(host=OLLAMA_HOST)


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
    """Validate LLM output before it reaches the database."""
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
                "date": occurred_on,
                "category": category,
                "event": event,
                "price": amount,
            }
        )

    return validated_expenses


@app.post("/webhook")
async def whatsapp_webhook(request: Request) -> str:
    """Receive a Twilio message and store validated expenses under its sender."""
    data = await request.form()
    user_message = str(data.get("Body", "")).strip()
    sender = str(data.get("From", "")).strip()
    message_sid = str(data.get("MessageSid", "")).strip()

    if not user_message or not sender:
        raise HTTPException(status_code=400, detail="Twilio webhook must include Body and From values.")

    print("\n===== NEW MESSAGE =====")
    print("Sender:", sender)
    print("Message:", user_message)

    with storage.SessionLocal() as session:
        user = storage.get_or_create_user(session, sender)
        webhook_event = None

        # Twilio can retry a successful request. A processed MessageSid must not
        # result in a second expense record.
        if message_sid:
            existing_event = storage.get_webhook_event(session, message_sid)
            if existing_event is not None and existing_event.status == "processed":
                print("Duplicate webhook ignored:", message_sid)
                return "OK"
            if existing_event is not None and existing_event.status == "processing":
                print("Webhook already being processed:", message_sid)
                return "OK"

            webhook_event = existing_event or storage.begin_webhook_event(session, message_sid, user)

        try:
            response = ollama_client.chat(
                model=OLLAMA_MODEL,
                messages=[{"role": "user", "content": build_extraction_prompt(user_message)}],
                format="json",
                think=False,
            )
            response_text = response.message.content

            print("\n===== QWEN RESPONSE =====")
            print(response_text)

            extracted_data: Any = json.loads(response_text)
            if not isinstance(extracted_data, dict):
                raise ValueError("The model response must be a JSON object.")

            expenses = validate_expenses(extracted_data.get("expenses", []))
            storage.save_expenses(
                session,
                user=user,
                webhook_event=webhook_event,
                expenses=expenses,
            )
        except Exception:
            storage.mark_webhook_failed(session, webhook_event)
            raise

    return "OK"


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)
