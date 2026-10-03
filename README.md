# Android AI Expense Tracker

An Android-only POC for learning LLM-powered workflows and orchestration, with MCP reserved for a possible future user chatbot. The application provides the chat interface and captures new SMS on the same phone.

**Available today:** the app has a Chat tab and an SMS capture tab. Chat calls a local help function through embedded Python and displays its reply. SMS capture retains incoming messages until cleared (20 cards per page) and displays Python's acknowledgement plus stage timings. Optional hosted classification calls the Python classifier directly to label messages **Expenditure** or **Other**, with separate inference timings. Expenditures then get a second hosted call to suggest your category/sub-category pair or return **Not sure** for both, displayed on the same card. Cards also show reported token usage and calculated per-attempt USD costs when supported, alongside all existing bottleneck timings. App version: `0.10.0-personal-categories`.

**Still planned:** transaction-detail extraction, expense storage, category confirmation/correction, and LLM chat answers. Every current chat message returns capability information; it does not yet interpret questions or record expenses.

Capture and built-in chat help work offline. Hosted classification needs internet, two dedicated API keys, and explicit upload consent. It needs no WhatsApp account, Twilio, ngrok, public webhook, or laptop service. Open the [Android project and setup guide](android-companion/README.md) to build, install, and run it.

For test locations, coverage, Windows commands, benchmarks, and manual checks, see the [Markdown testing wiki](docs/wiki/Testing.md).

## Progress — October 3, 2026

- SMS capture and the Kotlin-to-embedded-Python JSON handoff work on the Pixel 7a running Android 17. Real-message screenshots showed receiver entry to handoff result of **185.242 ms** when Python started and **7.548 ms** with Python already running. JSON encoding alone took **0.077 ms** and **0.027 ms**, respectively. These are two observations, not performance guarantees; they exclude SMS delivery and the later hosted classification.
- Hosted expenditure classification is implemented and installed. The user reports correct predictions for the messages tried on the phone. This verifies the live path for those samples, not a measured accuracy percentage or perfect classification for all messages.
- The few-shot prompt uses sanitized examples based on 25 outgoing-payment SMS and nine non-payment SMS, with additional synthetic counterexamples. This is prompting, not model training. Results and hosted-call latency appear separately from capture timings.
- On October 2, all **26 offline Python tests** and **14 Android checks** passed; debug APK builds and lint passed. Automated tests do not make paid API calls or establish model accuracy.
- On October 3, the unnecessary phone-side MCP layer was removed. All **27 offline Python tests** and **14 Android checks** passed; debug app/test APK builds and Android lint also passed. Version `0.6.0-direct-llm-classification` was installed on the Pixel over the existing app without clearing storage. Automated checks made no paid API calls; the updated live path still needs a synthetic SMS check.

**Scope decision:** sender/source validation and transaction ownership checks are deferred to avoid expanding the current POC. The classifier sees the SMS body, so a friend copying a bank alert can receive an Expenditure label. That label means the text describes an outgoing payment, not that the payment is verified as the user's. No source filter or ownership-confirmation feature has been added. Classification still does not insert an expense or affect spending totals.

Category identification uses your 24 labelled examples and a personal category/sub-category hierarchy. Transaction-detail extraction, review, and a local expense database come next. Ownership validation can be revisited later.

## Design decision: direct functions now, MCP later

The current workflow already knows which helper to call, and the helpers run in the same embedded Python runtime. MCP initialization, discovery, JSON-RPC transport, and tool dispatch add no needed capability here, so the client/server, redundant tool wrapper, and MCP-only tests/compatibility script have been removed. Their earlier implementation remains in Git history.

The application is still **LLM-powered and orchestrated**. Python entry points validate inputs and call the inference helpers. The Android worker sequences expenditure checking and category identification, rechecking permission/capture/consent between calls. This remains a fixed workflow; neither LLM call chooses its own tools. Strict structured output, Python parsing, and Kotlin result checks enforce the output format independently of MCP. They do not guarantee the prediction is correct.

MCP can be reconsidered when the future chatbot selects reusable tools such as recording or querying expenses. It is not required for today's capture, help, or classification. No autonomous agent loop or model training has been added.

| Python module | Responsibility |
| --- | --- |
| `orchestrator.py` | Validate SMS/chat payloads, return local receipts, call classification/category/help directly, and preserve safe result contracts. |
| `expenditure_classifier.py` | Build the expenditure prompt and parse a strict boolean or raise a safe error. |
| `category_identifier.py` | Build the personal category/sub-category prompt; return an allowed pair or Not sure for both, with separate failures. |
| `category_examples.py` | Personal hierarchy, sanitized user-labelled examples, and valid-pair checks; prompting, not training. |
| `llm_usage.py` | Whitelist reported token counts and calculate supported standard-tier USD estimates without logging private data. |
| `llm_api.py` | Shared HTTPS transport and response-envelope parsing for both inference stages. |
| `classifier_examples.py` | Sanitized labelled examples used by the prompt and offline tests. |
| `app_help.py` | Local capability/setup text; no network, credentials, or expense writes. |

## Category identification: current milestone

The worker calls `orchestrator.classify_sms` first. If it returns Expenditure, and permission, capture generation, retained attempt, and cloud consent still allow processing, the worker calls `orchestrator.identify_sms_category`. Each entry point delegates directly to its Python helper. Other messages and expenditure failures skip categories.

Your personal hierarchy (canonical lowercase labels):

| Category | Allowed sub-categories |
| --- | --- |
| `learning` | `engineering` |
| `utilities` | `transport`, `food`, `prepaid recharge`, `groceries`, `health` |
| `fun` | `gift`, `food` |
| `Not sure` | `Not sure` (uncertainty sentinel, not a spending category) |

The 24 user-labelled examples are sanitized in `category_examples.py`; `category_identifier.py` supplies the prompt and a two-field strict schema. Account/card suffixes, bank contact details, transaction references and dates/times are omitted or replaced. Merchant/payee names are retained to express this one-user POC's preferences; they are not universal labels for other users.

OPENAI/ChatGPT → learning/engineering; BMTC/metro/depot → utilities/transport; Indiqube and Chethan B S → utilities/food; Swiggy → fun/food; Toyloft → fun/gift; Airtel → utilities/prepaid recharge; Blinkit/Blink Commerce/Garden city chicken centr → utilities/groceries; Vaman clinic → utilities/health. The listed known personal payees use your transport preference. An **explicit different purpose takes priority**; unknown people, unspecified marketplaces and unclear mixed purchases return Not sure for both fields rather than inventing a label.

Python and Kotlin validate **both labels and their allowed pairing**. Structured output constrains format, not semantic correctness. Examples guide each request; they do not update model weights, learn automatically, or guarantee that all future payments to that payee have the same purpose. [Official OpenAI structured-output documentation](https://developers.openai.com/api/docs/guides/structured-outputs).

New results display category and sub-category on the same SMS card. Old flat suggestions remain explicitly marked as legacy, with sub-category not recorded; they are not silently converted or uploaded. Reclassification is an explicit new chargeable attempt. The same category key, model, expenditure gate, timing measurements and per-attempt calculated-cost telemetry remain in use. More prompt examples can increase input tokens; actual reported counts continue to drive estimates. No category-confirmation or training workflow is implemented.

Category failures are separate from expenditure classification. An expense-labelled message remains Expenditure even if the second request fails; no category is invented. Results are saved on the original captured-message card, not in an expense ledger. Older cards remain readable without a category, and updates do not upload them automatically. Manual reclassification repeats both stages for expenditures.

This adds a second API call/body upload, cost, and sequential latency for expenditures; Other messages use one call. Total analysis processing includes both stages; expenditure and category API timings are displayed separately. No human confirmation, transaction extraction, training, or expense insertion is included yet.

Verification for `0.8.0-message-costs`: **51 offline Python tests and 29 Android checks passed**; debug app/test APK builds and lint passed. The APK was installed on the Pixel 7a running Android 17 without clearing storage. No paid API calls were made; live category predictions still need a manual synthetic SMS check.

Verification for `0.10.0-personal-categories`: **54 offline Python tests and 42 Android checks passed**, plus debug app/test APK builds and lint. Installed on the Pixel without clearing history or remembered credentials. Tests validate sanitized examples, pair contracts, legacy compatibility, UI, and unchanged cost/timing behaviour—not live accuracy. No paid calls were made.

## Background processing update

Version `0.9.0-background-processing` adds opt-in encrypted credential/consent persistence and process-restart recovery without keeping the screen open. The 20-message eviction is removed; cards load in pages of 20 and older pending work remains eligible. Existing captures remain intact; already-evicted messages cannot be restored. At that milestone category examples were still starter assumptions; version 0.10 now uses your supplied labels.

Verification: **51 Python tests and 39 Android checks passed**; debug app/test APK builds and lint passed. Installed on the Pixel 7a without clearing history. The tests use synthetic keys/isolated files and make no paid calls. Actual closed-app SMS delivery, reboot recovery, and hosted predictions still require the manual check in the [Android guide](android-companion/README.md#background-processing-and-remembered-credentials).

## Cost attribution: two dedicated API keys

Create two different OpenAI keys named `financial-classification` and `category-identification`, then enter them in their matching masked fields under SMS capture. Both are required with upload consent; using the same key twice is rejected. The expenditure bridge passes only the classification key, and the category bridge passes only the category key. Both stages keep the shared model setting and existing prompts. Capture and local chat use neither key.

Use each key exclusively for its matching functionality. OpenAI's Costs API supports `api_key_ids` filtering and `group_by=api_key_id` in daily buckets, so the provider-reported monetary totals can be attributed to each functionality. This is not an immediate per-SMS bill; the app displays tokens, calculated costs and timings, but does not retrieve billing totals. [Official OpenAI Costs documentation](https://developers.openai.com/api/reference/resources/admin/subresources/organization/subresources/usage/methods/costs).

Keys are memory-only by default. Opt in to **Remember keys on this device** to encrypt keys/consent and restore background processing after process restarts. **Disable cloud and forget keys** removes the saved authorization. See [background behaviour and retention](android-companion/README.md#background-processing-and-remembered-credentials). Do not enter an organization Admin key in the phone app. Creating two keys does not change token prices or give each feature a separate billing account. Optional external evaluation must use a separate evaluation key if its cost should not be mixed with app usage.

### Per-message calculated cost and bottleneck timings

Each analysed SMS card now shows API-reported input/output tokens, cached input and cache-write tokens when supplied, a calculated USD cost per stage, and a combined **Calculated cost this attempt** when every required stage has a known estimate. Other messages require only classification; expenditures include the category call. This preserves the existing receiver, JSON encoding, Python startup/handoff, separate hosted-call, and total-analysis timings. Timings do not include SMS delivery or WorkManager scheduling wait.

Costs include the whole request (instructions, examples, schema and SMS), not just the SMS text. Output usage already includes reasoning; it is not added again. The app requests `service_tier: default` and currently prices only the exact reported `gpt-5.6-luna` model on that reported tier, with rates verified **2026-10-03**. USD per million tokens: ordinary input **0.20**, cached input **0.02**, cache writes **0.25**, output **1.20**. Cache reads/writes are subtracted from total input before ordinary-input pricing. Requests above 272,000 input tokens use the documented full-request long-context premium. Rates/date are preserved with each estimate; update the pricing module when rates change. [Model/pricing documentation](https://developers.openai.com/api/docs/models/gpt-5.6-luna), [cache accounting](https://developers.openai.com/api/docs/guides/prompt-caching), [response usage fields](https://developers.openai.com/api/reference/python/resources/responses/methods/create).

Missing or malformed usage, missing cache breakdown, an unknown model or tier, and network failures show **Unavailable**, not zero. Valid predictions remain valid even when telemetry is absent. Refused/incomplete/malformed model outputs retain usage and costs if the provider supplies them, and failures now retain measured call time. A category failure never erases the expenditure label or first-stage usage.

These are calculated estimates, not provider-reported bills. They exclude account-specific adjustments, taxes and currency conversion. The latest attempt replaces the card's previous analysis; earlier retries, discarded/cancelled calls and cleared history are not a spending audit. Never assume a timeout or missing usage means the request was free. Use the two dedicated keys and OpenAI's external cost reporting to reconcile totals; the app does not store Admin credentials or fetch billing records.

For a manual check, re-enter both keys and consent, send a synthetic BMTC payment, and inspect two sets of tokens/costs plus the combined amount alongside timings. Send an advertising SMS and expect only classification cost with category skipped. Old cards still load without usage and are not uploaded automatically. Reclassifying them is an explicit new, potentially chargeable attempt.

## Current folder structure

Generated builds, local SDK settings, virtual environments, and private phone data are omitted.

```text
expense-tracker-with-mcp/
├── README.md
├── docs/wiki/Testing.md
└── android-companion/
    ├── README.md
    ├── app/build.gradle.kts
    ├── app/src/main/
    │   ├── AndroidManifest.xml
    │   ├── java/com/abijith/expensetracker/
    │   │   ├── MainActivity.kt, ChatScreen.kt, ChatViewModel.kt
    │   │   ├── SmsReceiver.kt, SmsPayload.kt, SmsStore.kt
    │   │   ├── PythonBridge.kt, SmsPerformance.kt, LlmUsage.kt
    │   │   ├── SmsClassification.kt, SmsClassificationWorker.kt, SmsCategory.kt
    │   │   ├── CloudClassificationSettings.kt, CloudClassificationControls.kt
    │   │   ├── CloudCredentialVault.kt, FinanceTrackerApplication.kt
    │   │   └── ui/theme/
    │   ├── python/
    │   │   ├── orchestrator.py
    │   │   ├── expenditure_classifier.py
    │   │   ├── classifier_examples.py
    │   │   ├── category_identifier.py
    │   │   ├── category_examples.py
    │   │   ├── llm_api.py
    │   │   ├── llm_usage.py
    │   │   └── app_help.py
    │   └── res/
    ├── app/src/androidTest/java/com/abijith/expensetracker/
    │   ├── PythonBridgeInstrumentedTest.kt
    │   ├── ChatScreenInstrumentedTest.kt
    │   ├── SmsPerformanceInstrumentedTest.kt
    │   ├── ClassifierInstrumentedTest.kt
    │   ├── CategoryInstrumentedTest.kt
    │   ├── ClassificationStoreInstrumentedTest.kt
    │   ├── CloudControlsInstrumentedTest.kt
    │   ├── CloudKeysInstrumentedTest.kt
    │   ├── CloudPersistenceInstrumentedTest.kt
    │   ├── SmsHistoryInstrumentedTest.kt
    │   └── LlmUsageInstrumentedTest.kt
    ├── python-tests/
    │   ├── test_orchestrator.py, test_chat.py, test_classifier.py, test_category.py, test_usage.py
    │   ├── benchmark_handoff.py
    │   └── evaluate_classifier.py
    └── Gradle build/settings files, wrapper, and version catalogue
```

See the [Android code map](android-companion/README.md#code-map) for Kotlin responsibilities and the [testing wiki](docs/wiki/Testing.md) for test locations and commands.

## Current implementation: SMS and app chat

Solid arrows show normal processing. Dotted arrows show what the separately started tests exercise. Python runs inside the APK through Chaquopy.

```mermaid
flowchart TB
    subgraph Phone[Current application on the Android phone]
        Chat[Chat tab] --> ChatWork[ChatViewModel<br/>background executor]
        SMS[New incoming SMS] --> Capture[SmsReceiver<br/>permission and capture checks]
        Capture --> Save[SmsStore<br/>save new message as Pending]
        Save -->|If capture remains active| Bridge[PythonBridge<br/>shared embedded Python runtime]
        ChatWork --> Bridge
        Bridge -->|SMS payload| SMSPython[orchestrator.handle_sms<br/>validate and return receipt]
        Bridge -->|Chat payload| ChatPython[orchestrator.handle_chat<br/>validate and request help]
        SMSPython --> SMSResult[Bridge checks receipt or failure<br/>SmsStore updates original message]
        SMSResult --> SMSScreen[SMS capture tab<br/>status, receipt, and elapsed time]
        SMSResult -->|Only when cloud consent and both keys are active| CloudWork[WorkManager<br/>separate network-constrained job]
        CloudWork --> Classifier[orchestrator.classify_sms<br/>validate and request classification]
        Classifier --> ClassifyTool[expenditure_classifier.classify_expenditure<br/>few-shot prompt and hosted API call]
        ChatPython --> HelpTool[app_help.get_help<br/>local capability information]
        ClassifyTool --> Gate[Android worker<br/>check expenditure and current consent/capture]
        Gate -->|Other or failure: skip category| Classified[SmsStore updates same message<br/>classification, category, usage, calculated cost or safe failure]
        Gate -->|Expenditure and still allowed| CategoryPython[orchestrator.identify_sms_category]
        CategoryPython --> CategoryTool[category_identifier.identify_category<br/>few-shot category prompt and strict enum]
        CategoryTool -->|Result returns through orchestrator and bridge| Classified
        Classified --> SMSScreen
        HelpTool -->|Result returns through orchestrator and bridge| ChatResult[Bridge checks reply or failure<br/>ChatViewModel updates conversation]
        ChatResult --> ChatScreen[Chat tab<br/>reply and elapsed time]
    end
    ClassifyTool <-->|Financial classification key| API[Hosted OpenAI Responses API<br/>strict structured output]
    CategoryTool <-->|Category identification key| API

    subgraph Checks[Verification - run separately]
        Unit[54 Python unit tests<br/>offline, API mocked; token accounting checked]
        Integration[4 bridge tests and 1 chat screen test<br/>Pixel or emulator]
        Performance[3 performance checks<br/>JSON encoding, warm bridge, and timing format]
        ClassificationChecks[34 classifier, category, history, consent UI, key-routing, cost, and encrypted-recovery checks<br/>no valid API key or external requests]
        Manual[Manual SMS and usability checks]
    end
    Unit -.->|Direct function calls| SMSPython
    Unit -.->|Direct function calls| ChatPython
    Integration -.->|Call packaged Python| Bridge
    Integration -.->|Enter text, send, and switch tabs| Chat
    Performance -.->|Synthetic message measurements| Bridge
    Unit -.->|Mock HTTP and validate parsing| ClassifyTool
    Unit -.->|Strict category parsing and safe failures| CategoryTool
    ClassificationChecks -.->|Packaged parsers, routing, and missing-key paths| Classifier
    ClassificationChecks -.->|Expenditure-only gate and category display| CategoryPython
    ClassificationChecks -.->|Isolated storage and stub consent controls| Classified
    Manual -.->|Receive an actual SMS| SMS
    Manual -.->|Inspect capture controls and saved history| SMSScreen
```

Tests do not run each time an SMS arrives or a chat message is sent. Python unit tests use your computer's interpreter. Android integration tests use the real bridge and the Python bundled in the APK. The screen test types a message, checks the help reply, and switches tabs. These tests do not send SMS or clear saved capture history.

SMS capture and cloud uploads start off on fresh installation. Enter both keys privately and accept upload consent. Keys are memory-only unless **Remember keys on this device** is selected, which encrypts credentials and consent for process-restart recovery without an open screen. Disabling cloud also forgets saved keys. Settings restoration works for SMS/worker-triggered starts; Android Force stop still requires reopening. Enabling does not replay history; per-card reclassification is an explicit upload. History is retained until cleared, with 20 cards per display page and a Show older messages button. Direct personal keys remain a POC tradeoff, not a distribution design. See the [background setup and manual checks](android-companion/README.md#background-processing-and-remembered-credentials).

The cloud request sends the complete SMS body, which may contain sensitive information; it does not separately send the sender, message ID, or phone metadata. Use synthetic SMS for demos. WorkManager stores only IDs and tokens. It waits for network/system scheduling, but is not a latency optimization. Errors are not classified as Other; retry is manual. Duplicate broadcasts are ignored, and pause/clear invalidates pending updates. Already-sent requests cannot be recalled. Chat is session-only: it keeps the latest 60 entries in memory, survives screen rotation and tab changes, and resets after the app process ends. Captured SMS history remains stored separately on the phone.

### How you run the checks

Start each check from PowerShell or Android Studio. GitHub Actions is not configured; pushing code does not currently run the tests.

```mermaid
flowchart TB
    Developer[Developer starts a check]
    Developer --> Shell[PowerShell<br/>activate Python environment and run unittest]
    Shell --> UnitTests[54 tests check capture, chat, classifier, categories, and costs<br/>synthetic input and mocked HTTP]
    UnitTests --> Terminal[Terminal shows pass or fail<br/>and failed assertion details]

    Developer --> Studio[Android Studio Run test<br/>or Gradle connectedDebugAndroidTest]
    Studio --> Install[Build and install app plus test APK<br/>on Pixel or emulator]
    Install --> Runner[AndroidJUnitRunner<br/>42 checks: bridge, UI, performance, classification, categories, costs, and encrypted recovery]
    Runner --> Results[Android Studio or Gradle shows pass or fail]

    Developer --> Benchmark[Optional Python benchmark<br/>100 SMS handoffs after warm-up]
    Benchmark --> Timing[Host median and 95th percentile<br/>no pass/fail threshold]
    Developer --> LiveEval[Optional paid classifier evaluation<br/>sanitized held-out messages]
    LiveEval --> Accuracy[Real model predictions and API latency<br/>not part of offline tests]
```

Before a demo, run the Python suite, run the Android suites, then do the manual SMS checks. Read a failed assertion, correct the behavior or an incorrect expectation, and rerun the affected suite. The optional benchmark measures host Python processing, excluding Android startup and SMS delivery. See the [test commands](android-companion/README.md#automated-checks) and [manual demo checklist](android-companion/README.md#manual-checks-on-your-pixel-7a).

For bottleneck testing, new SMS cards show receiver entry to JSON readiness, JSON encoding time, and the individual setup/wait/save/Python stages. Device benchmarks report median and 95th-percentile serialization and warm bridge timings. Actual SMS delivery and app detection cannot be isolated from the SMS-provided timestamp alone; see the [measurement definitions and device benchmark guide](android-companion/README.md#sms-bottleneck-measurements).

## Goal: one Android application

1. Install the APK and open its Chat tab for instructions.
2. Enable SMS access to begin automatic tracking.
3. An incoming SMS triggers background processing. The Python orchestrator identifies expenditures, extracts transaction details, and proposes a category.
4. The app asks for category confirmation in its own chat. Replies are attached to the correct pending transaction.
5. The orchestrator records the reviewed expense through local database functions.
6. Ask spending questions in the same chat. A future query handler may select database tools through MCP; the app displays stored-data results.

### Planned architecture

```mermaid
flowchart TB
    subgraph Phone[Android phone - proposed complete POC]
        SMS[Incoming SMS] --> Work[Native receiver and scheduled background work]
        Work --> Orchestrator[Embedded Python orchestrator]
        UI[App chat, setup, review, and status] <--> Orchestrator
        Orchestrator --> Classifier[Financial-message classifier]
        Classifier -->|Expenditure| Category[Extract transaction and propose category]
        Category --> Review[Category validation and saved feedback]
        Review --> Storage[Local database functions]
        Storage <--> DB[(Local SQLite<br/>expenses, reviews, and feedback)]
        Orchestrator --> Query[Future user-query handler]
        Query --> Client[Optional future MCP client<br/>approved query tools]
        Client --> Server[Optional future expense MCP server]
        Server <--> DB
        Query -->|Stored-data answer| UI
        Classifier <--> Model[Hosted LLM API]
        Category <--> Model
        Query <--> Model
        Orchestrator -->|Review questions and weekly reports| UI
    end
```

SMS capture, local help, boolean classification, and category suggestions exist today. Transaction extraction, review, expense storage, query planning, MCP, and weekly reports in this goal diagram are future work. The intended database and orchestration run on the phone; no public application endpoint is needed.

MCP remains deferred. Both inference stages use the existing model and shared safe transport; expenditure prompts/results are unchanged. Category identification adds a second upload for expenditures, with updated consent text and separate category timings. The classifier does not select tools, write expenses, or train itself. Local help does not call the LLM. The current default and model override are documented in the [Android setup guide](android-companion/README.md#set-up-hosted-expenditure-classification).

### Five agent roles

| Role | Planned responsibility |
| --- | --- |
| Financial-message classifier | Decide whether an SMS describes an expenditure; treat processing failures separately from non-financial messages. |
| Category identification | Suggest a category or return uncertainty (implemented); transaction amount, merchant, currency, and date extraction comes later. |
| Category validation | Interpret confirmation or correction in the app chat, using the stored pending transaction ID. |
| Training / feedback | Save verified examples and merchant preferences for future predictions; model fine-tuning is a later experiment. A confirmation alone does not retrain a model or prove improved accuracy. |
| User-query handler | Interpret spending questions and use approved database tools; MCP is a possible later tool interface. |

These are logical roles for learning, not five continuously running models. The Python orchestrator controls which role runs next. Pending reviews must be stored: do not keep a Python call open while waiting for a reply. A late correction updates the existing expense. Unanswered proposed categories remain unconfirmed and are excluded from verified feedback.

### Next verifiable steps

1. Initial live classification is working in the user's phone checks. Broaden the synthetic expenditure/other evaluation and optionally run the paid held-out evaluation to record accuracy and latency. Offline mocked tests do not establish model accuracy; sender/ownership validation is deferred.
2. Evaluate the personal category/sub-category predictions, then add transaction-detail extraction; verify amount, merchant, currency, date, and uncertainty handling.
3. Add category review, correction, saved feedback, and local SQLite expense storage.
4. Connect chat queries to the LLM and approved database tools; consider MCP at this point and replace the help-only response.
5. Add weekly reports shown in the app, with Android scheduling/notifications if background reminders are needed.

Network/model work uses separately scheduled Android background work. The receiver performs a short local acknowledgement within its limited execution window and enqueues classification; it does not wait for inference.

## Earlier WhatsApp POC

The [v0.1.0 release](https://github.com/Abijith97/expense-tracker-with-mcp/releases/tag/v0.1.0) preserves the earlier WhatsApp/Twilio/FastAPI/Ollama/MCP solution. The unused root-level backend Python files and its `requirements.txt` have been removed from the current source tree. The active implementation and tests are under `android-companion/`; use Android Studio to run the app, not a laptop Python server or ngrok.


The earlier cleanup removed unused backend files and template scaffolding. This update additionally removes the phone-side MCP modules and MCP-only checks, replacing them with direct classification/help calls. Functional tests, benchmarks, optional model evaluation, and phone capture storage remain. Removed source can be recovered from Git history; captured messages, credentials, databases, and development environments are not deleted.
