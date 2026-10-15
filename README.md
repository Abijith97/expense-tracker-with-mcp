# Android AI Expense Tracker

## Install on your Android phone

1. Open [GitHub Actions](https://github.com/Abijith97/expense-tracker-with-mcp/actions/workflows/ci.yml) and select the latest successful **main** run of **Tests, coverage and demo APK**. Sign in to GitHub to download artifacts; the repository is public.
2. Under **Artifacts**, download **expense-tracker-demo-apk**. Extract the ZIP and open **app-debug.apk** on your phone. Do not choose the internal/test APK artifact.
3. If Android prompts you, allow installation from the browser or file manager you are using, then install the app. Only install downloads you trust; Android/device policies may restrict sideloading.
4. Open the app, enable SMS capture and grant SMS permission. To classify messages, enter your own two distinct OpenAI API keys and enable cloud processing with upload consent. API usage can cost money.

This POC targets compatible 64-bit Android phones running Android 8.0 or later; verified devices/runtimes are the Pixel 7a and the CI emulator, not every Android phone. It captures **new SMS**, not existing inbox history, RCS or MMS. Your messages stay on your phone unless you enable hosted processing. No laptop server or Python installation is needed on the phone.

## Update an installed app

1. After a new **main** CI run succeeds, download **expense-tracker-demo-apk** again.
2. Extract it, open **app-debug.apk**, and choose **Update**. The APK is used for both installation and updates—there is no separate update file.
3. Reopen the app and check that SMS capture/cloud settings are as intended. Updates are manual, not automatically installed.

Compatible updates keep the app's local history and saved reviews/settings. **Do not uninstall first:** uninstalling removes this app's local data. If an older developer/CI installation reports a package conflict, stop rather than uninstalling to bypass it; see the [developer install/update notes](docs/wiki/Demo-APK-Updates.md). Artifacts expire after 14 days; use a newer successful run when necessary.

## What the app does

An Android finance-tracking POC, currently running on a Pixel 7a with Android 17. App version: `0.14.0-demo`.

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

The app uses **Kotlin/Jetpack Compose for Android and embedded Python for orchestration and OpenAI requests**. CI runs Python and Android emulator tests, including complete SMS happy/failure paths, coverage, APK builds and lint. Automated tests use mocked API responses; they do not measure live model accuracy.

[GitHub Actions runs tests, coverage and demo APK builds](docs/wiki/Testing.md#continuous-integration-and-coverage).

## Earlier WhatsApp POC

The [v0.1.0 release](https://github.com/Abijith97/expense-tracker-with-mcp/releases/tag/v0.1.0) preserves the earlier WhatsApp/Twilio/FastAPI/Ollama/MCP solution. The unused root-level backend Python files and its `requirements.txt` have been removed from the current source tree. The active implementation and tests are under `android-companion/`; use Android Studio to run the app, not a laptop Python server or ngrok.
