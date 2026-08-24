"""WhatsApp webhook that extracts expenses with Ollama and stores them in Google Sheets."""

import json
import os
from datetime import datetime
from typing import Any

import gspread
from fastapi import FastAPI, Request
from google.oauth2.service_account import Credentials
from ollama import Client


# The FastAPI application is imported by an ASGI server (for example, Uvicorn).
app = FastAPI()

# Connection details for the local Ollama instance and Google Sheets workbook.
# Use a client-specific setting so Ollama's server-side `OLLAMA_HOST` setting
# does not accidentally point this application at its bind address (for
# example, `0.0.0.0:11434`, which is not a valid destination to connect to).
OLLAMA_HOST = os.getenv("OLLAMA_CLIENT_HOST", "http://127.0.0.1:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen3:8b")
SPREADSHEET_NAME = os.getenv("SPREADSHEET_NAME", "Expense_Tracker")
SERVICE_ACCOUNT_FILE = os.getenv("SERVICE_ACCOUNT_FILE", "service-account.json")

GOOGLE_SCOPES = [
    "https://www.googleapis.com/auth/spreadsheets",
    "https://www.googleapis.com/auth/drive",
]

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


# Set up the external clients once when the application starts.
ollama_client = Client(host=OLLAMA_HOST)
credentials = Credentials.from_service_account_file(
    SERVICE_ACCOUNT_FILE,
    scopes=GOOGLE_SCOPES,
)
google_client = gspread.authorize(credentials)
sheet = google_client.open(SPREADSHEET_NAME).sheet1


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
- Resolve relative dates such as:
  - today
  - yesterday
  - day before yesterday
  - last Monday
  - last Sunday
  - last week
- If no date is mentioned, use today's date.
- Price must be numeric without currency symbols.
- Choose the most appropriate category from the valid categories list.
- Preserve important event details.
- Return JSON only.
- Do not include markdown, explanations, or additional text.

Message:
{user_message}
"""


def append_expenses(expenses: list[dict[str, Any]]) -> None:
    """Append the extracted expense records to the first worksheet."""
    for expense in expenses:
        sheet.append_row(
            [
                expense["date"],
                expense["category"],
                expense["event"],
                expense["price"],
            ]
        )
        print(
            f"Added: {expense['date']} | {expense['category']} | "
            f"{expense['event']} | {expense['price']}"
        )


@app.post("/webhook")
async def whatsapp_webhook(request: Request) -> str:
    """Receive a WhatsApp message, extract expenses, and persist them."""
    # Twilio sends WhatsApp webhook values as form data.
    data = await request.form()
    user_message = data.get("Body", "")
    sender = data.get("From", "Unknown")

    print("\n===== NEW MESSAGE =====")
    print("Sender:", sender)
    print("Message:", user_message)

    # Ask Qwen to return one structured record per spending event.
    response = ollama_client.chat(
        model=OLLAMA_MODEL,
        messages=[{"role": "user", "content": build_extraction_prompt(user_message)}],
        format="json",
        think=False,
    )
    response_text = response.message.content

    print("\n===== QWEN RESPONSE =====")
    print(response_text)

    # The model is instructed to return {"expenses": [...]} and nothing else.
    extracted_data = json.loads(response_text)
    expenses = extracted_data.get("expenses", [])

    if not isinstance(expenses, list):
        raise ValueError("The model response contains an invalid expenses field.")

    # A non-expense message can legitimately produce no records.
    append_expenses(expenses)

    return "OK"


if __name__ == "__main__":
    # Running `python main.py` starts the development server on localhost.
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)
