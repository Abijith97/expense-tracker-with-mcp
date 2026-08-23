from fastapi import FastAPI, Request
from ollama import Client
import gspread
from google.oauth2.service_account import Credentials
import json
from datetime import datetime

app = FastAPI()


# =========================
# Ollama
# =========================

ollama_client = Client(
    host="http://172.24.80.1:11434"
)


# =========================
# Google Sheets
# =========================

SCOPES = [
    "https://www.googleapis.com/auth/spreadsheets",
    "https://www.googleapis.com/auth/drive"
]

creds = Credentials.from_service_account_file(
    "service-account.json",
    scopes=SCOPES
)

google_client = gspread.authorize(creds)

sheet = google_client.open("Expense_Tracker").sheet1


# =========================
# WhatsApp Webhook
# =========================

@app.post("/webhook")
async def whatsapp_webhook(request: Request):

    data = await request.form()

    user_message = data.get("Body")
    sender = data.get("From")

    print("\n===== NEW MESSAGE =====")
    print("Sender:", sender)
    print("Message:", user_message)


    # =========================
    # Send message to Qwen3
    # =========================

    today = datetime.now().strftime("%Y-%m-%d")

    prompt = f"""
You are an expense extraction system.

Today's date is {today}.

Valid categories:
- Food
- Transport
- Groceries
- Family
- Gifts
- Utilities
- Clothing
- Investments
- Home & Kitchen
- Family & Leisure
- Family & Celebration
- Snacks
- Other

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

    response = ollama_client.chat(
        model="qwen3:8b",
        messages=[
            {
                "role": "user",
                "content": prompt
            }
        ]
    )


    # =========================
    # Parse JSON
    # =========================

    response_text = response.message.content

    print("\n===== QWEN RESPONSE =====")
    print(response_text)

    expenses = json.loads(response_text)


    # =========================
    # Update Google Sheets
    # =========================

    for expense in expenses["expenses"]:

        sheet.append_row([
            expense["date"],
            expense["category"],
            expense["event"],
            expense["price"]
        ])

        print(
            f"Added: {expense['date']} | "
            f"{expense['category']} | "
            f"{expense['event']} | "
            f"{expense['price']}"
        )


    return "OK"