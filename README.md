# WhatsApp AI Expense Tracker

## End goal architecture

This proof of concept is a multi-user WhatsApp finance tracker. A user is identified by their WhatsApp number, logs expenses in natural language, and asks simple questions such as “How much did I spend on food last week?” It remains a local development project; production hosting, scheduled reports, dashboards, billing, and enterprise onboarding are out of scope for now.

```mermaid
flowchart TB
    User[WhatsApp user] -->|Expense or question| Twilio[Twilio WhatsApp Sandbox]
    Twilio -->|HTTPS POST webhook| Ngrok[ngrok public tunnel]
    Ngrok -->|Forward to port 8000| API[FastAPI POC application]

    subgraph Laptop[Local Windows development environment]
        API --> Identity[User identity by WhatsApp number<br/>and webhook deduplication]
        Identity --> LLM[Ollama / Qwen<br/>extract expense or understand question]
        LLM --> Agent[Simple conversation orchestrator<br/>MCP client]
        Agent -->|MCP tools| MCP[Expense MCP server]
        MCP --> Database[(SQLite database<br/>users and expenses)]
    end
```

The MCP server exposes focused operations such as `record_expense`, `get_weekly_summary`, `compare_weeks`, and `get_category_trend`. The database—not the LLM—calculates totals and comparisons.
