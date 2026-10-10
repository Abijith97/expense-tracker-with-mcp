# Android AI Expense Tracker

An Android finance-tracking POC, currently running on a Pixel 7a with Android 17. App version: `0.13.0-expenditure-review`.

It processes messages through this flow:

**SMS received → saved locally → expenditure classification → category identification → your review**

Its current capabilities are:

- **SMS capture:** With permission and capture enabled, it receives SMS messages and displays them as cards. Background processing uses Android WorkManager.
- **Expenditure classification:** OpenAI identifies whether a message describes an expenditure. The prompt excludes credit-card bill repayments while counting purchases made using a credit card.
- **Category identification:** Expenditures receive a suggested category and sub-category, using your examples and preferences. Ambiguous messages return “Not sure.”
- **Category review:** You can confirm suggestions, correct them, enter custom labels, and edit saved labels. Your choices remain separate from the original model prediction.
- **Recording choice:** Each expenditure card has a switch that saves whether you want it included. This currently stores your choice, rather than creating a ledger entry.
- **Diagnostics:** Cards show processing times, request counts, token usage, and estimated API costs where available. Temporary network failures receive bounded retries.
- **Local storage and credentials:** Captured messages and submitted reviews persist on the phone. Two separate API keys support classification and categorization, with optional encrypted storage. Hosted classification requires internet and upload consent.
- **Chat and testing:** The in-app chat provides usage help. Debug builds include SMS simulation for testing without another phone.

The app uses **Kotlin/Jetpack Compose for Android and embedded Python for orchestration and OpenAI requests**. Verification on October 10, 2026: **56 Python tests and 80 Android tests passed**, along with APK builds and lint. Automated tests use mocked API responses; these results do not measure live model accuracy.

## Earlier WhatsApp POC

The [v0.1.0 release](https://github.com/Abijith97/expense-tracker-with-mcp/releases/tag/v0.1.0) preserves the earlier WhatsApp/Twilio/FastAPI/Ollama/MCP solution. The unused root-level backend Python files and its `requirements.txt` have been removed from the current source tree. The active implementation and tests are under `android-companion/`; use Android Studio to run the app, not a laptop Python server or ngrok.
