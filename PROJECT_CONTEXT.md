# PROJECT_CONTEXT.md — WhatsApp AI Expense Tracker

## Goal

Build a personal AI-powered expense tracker where the user sends natural-language expense messages through WhatsApp. The system extracts structured expense data with a local LLM and records it in Google Sheets.

Current implementation is intentionally **without MCP**. MCP is a future experiment.

## Current Architecture

WhatsApp
→ Twilio WhatsApp Sandbox
→ webhook
→ ngrok (Windows)
→ FastAPI (Windows, port 8000)
→ Ollama (Windows, port 11434)
→ Qwen3 8B
→ structured JSON
→ Python validation/processing
→ Google Sheets API
→ Expense Tracker Google Sheet

Environment:

Windows:
- Ollama + Qwen3 8B
- ngrok
- FastAPI/Python application
- Uvicorn on localhost:8000

The application connects to Ollama at:

http://127.0.0.1:11434

This can be verified with:

curl http://172.24.80.1:11434

which returned:

Ollama is running

## Phase 1 — WhatsApp/Twilio/FastAPI/ngrok

Completed.

Twilio WhatsApp Sandbox receives WhatsApp messages and triggers the configured webhook via HTTP POST.

FastAPI runs on Windows:

uvicorn main:app --reload

FastAPI listens on:

http://127.0.0.1:8000

ngrok runs on Windows:

ngrok http 8000

Twilio's webhook points to the public ngrok URL, typically:

https://<ngrok-url>/webhook

The ngrok URL changes when a new free session is started.

The current setup runs FastAPI and ngrok directly on Windows.

## Phase 2 — LLM

Claude was originally used, with `claude-sonnet-4-6` being the working model. Claude has now been replaced with a local LLM.

Current LLM:

Qwen3 8B via Ollama.

Ollama and the Python application both run on Windows and connect explicitly:

```python
from ollama import Client

client = Client(
    host="http://127.0.0.1:11434"
)
```

Call:

```python
message = client.chat(
    model="qwen3:8b",
    messages=[...]
)
```

`test_llm.py` successfully works.

`test_llm.py` does not require FastAPI, ngrok, or Twilio; it only requires Ollama/Qwen3.

## Expense Extraction

Current categories:

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

Prompt requirements:
- Extract all expenses mentioned.
- One entry per purchase/spend event.
- Date is mandatory.
- Use ISO `YYYY-MM-DD`.
- Resolve today, yesterday, day before yesterday, last Monday/Sunday, last week, etc.
- If no date is mentioned, use today's date.
- Price must be numeric without currency symbols.
- Choose the most appropriate valid category.
- Preserve useful event details.
- Return JSON only.

Schema:

```json
{
  "expenses": [
    {
      "date": "YYYY-MM-DD",
      "category": "Category Name",
      "event": "Description",
      "price": 0
    }
  ]
}
```

Current parsing:

```python
response_text = message.message.content
data = json.loads(response_text)
```

Future improvement: use Ollama structured outputs/JSON schema rather than relying only on the prompt's JSON instruction.

## Phase 3 — Google Sheets

Completed independently.

Google Cloud setup:
- Google Sheets API enabled
- Google Drive API enabled
- Service account created
- Service account JSON credentials downloaded
- Google Sheet shared with service account email as Editor

Python packages:

```bash
pip install gspread google-auth
```

Scopes:

```python
SCOPES = [
    "https://www.googleapis.com/auth/spreadsheets",
    "https://www.googleapis.com/auth/drive"
]
```

Authentication:

```python
creds = Credentials.from_service_account_file(
    "service-account.json",
    scopes=SCOPES
)

client = gspread.authorize(creds)
sheet = client.open("Expense_Tracker").sheet1
```

Rows are appended with:

```python
sheet.append_row([
    expense["date"],
    expense["category"],
    expense["event"],
    expense["price"]
])
```

The Google Sheets write test works.

Sheet columns:

Date | Category | Event | Price

## Intended Integrated Flow

1. WhatsApp message arrives.
2. Twilio sends POST to `/webhook`.
3. FastAPI extracts the message.
4. Python sends it to Qwen3 via Ollama.
5. Qwen3 returns structured JSON.
6. Python parses/validates JSON.
7. Python appends every expense to Google Sheets.
8. FastAPI returns success to Twilio.

Example input:

I spent Rs 200 on lunch today and Rs 23 on the bus.

Expected structure:

```json
{
  "expenses": [
    {
      "date": "YYYY-MM-DD",
      "category": "Food",
      "event": "Lunch",
      "price": 200
    },
    {
      "date": "YYYY-MM-DD",
      "category": "Transport",
      "event": "Bus",
      "price": 23
    }
  ]
}
```

## No MCP Yet

MCP is NOT currently implemented.

Current orchestration:

```text
FastAPI
 ├── calls Ollama
 └── calls Google Sheets API
```

There is no MCP server and Claude is no longer used.

Future MCP experiment:

```text
WhatsApp
→ LLM
→ MCP Client
→ MCP Server
→ Google Sheets tool
```

Do not describe the current project as MCP-based.

## Security

Never commit secrets.

`.gitignore` should include:

```text
.env
service-account.json
```

The Google service-account JSON is private.

## Suggested Refactoring

A possible future structure:

```text
whatsapp-expense-tracker/
├── main.py
├── test_llm.py
├── test_sheet.py
├── prompts.py
├── llm_service.py
├── sheets_service.py
├── validation.py
├── service-account.json  # DO NOT COMMIT
├── .env                 # DO NOT COMMIT
├── .gitignore
└── requirements.txt
```

This is a recommendation, not necessarily the exact current filesystem.

## Immediate Next Steps

1. Integrate Qwen3 into `main.py`.
2. Receive Twilio WhatsApp message.
3. Send message to Qwen3.
4. Parse/validate JSON.
5. Append all expenses to Google Sheets.
6. Test WhatsApp → Sheets end-to-end.
7. Add WhatsApp confirmation response.
8. Add robust malformed-output/error handling.
9. Add duplicate webhook protection.
10. Later experiment with MCP.

## Resume Description

A technically accurate description:

**AI-Powered Expense Tracker | Python, FastAPI, Ollama, Qwen3, Twilio, Google Sheets**

- Built an event-driven expense tracking application using WhatsApp as the user interface and Twilio webhooks for real-time message processing.
- Integrated a self-hosted Qwen3 8B LLM through Ollama to extract structured expense data from natural-language messages.
- Developed a FastAPI backend to process and validate LLM-generated expense records and automatically persist them to Google Sheets using the Google Sheets API.
- Configured a Windows development environment for local LLM inference while maintaining cloud-based WhatsApp and spreadsheet integrations.

## Current Status

Completed:
- Twilio WhatsApp Sandbox
- Twilio → webhook
- FastAPI webhook
- ngrok tunnel
- Ollama installation
- Qwen3 8B installation
- Windows-only Ollama networking
- Local LLM extraction test
- Google Sheets API
- Service account authentication
- Google Sheets write test

Current task:
- Integrate WhatsApp + FastAPI + Qwen3/Ollama + Google Sheets into one non-MCP application.

Future:
- WhatsApp confirmation messages
- Robust validation/error handling
- Duplicate protection
- Conversational queries/reports
- MCP experiment
