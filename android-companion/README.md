# Android app — category identification and separate API keys

The Android app provides Chat, SMS cards, timing/cost diagnostics, hosted expenditure/category inference, local recording switches and editable category validation. Python runs inside the APK. App version: `0.13.0-expenditure-review` (`versionCode` 15). Debug builds also provide **Simulate SMS** to test without another phone.

Chat currently calls the local `app_help.get_help` function for every message, including what the app can do and which finance features are planned. Chat does not yet perform LLM inference, record expenses, or answer spending questions. SMS classification calls `expenditure_classifier.classify_expenditure` directly, which can call OpenAI after you enter two dedicated keys and consent. The app runs without a WhatsApp account, Twilio, ngrok, or a separately hosted application backend.

Capture starts **off** on a fresh installation. When you enable it and grant Android's SMS permission, the app retains newly received SMS messages in app-private local storage until you clear them. The screen initially shows 20 cards, with **Show older messages** for additional pages. Each entry contains the sender, body, SMS timestamp, capture timestamp, handoff status, and any classification result. All ordinary SMS messages are captured while enabled, including non-financial messages.

The capture/chat entry points validate input and return a receipt or help without network calls. A background classification entry point calls the classifier through the standard-library HTTPS client and OpenAI Responses API. Expenditures get a separate category-identification call. Not sure/Not sure results get a Kotlin Chat form; submitted labels are saved locally, not passed through inference. No extra Python packages or phone Python installation are needed. Transaction-detail extraction and finance-database updates remain planned.

Progress on October 3, 2026: the user reports that live phone classification is working and correctly predicts the messages tested. This is a manual sample check, not a measured accuracy percentage. Sender/source validation and ownership confirmation are intentionally deferred: a transaction alert copied by a friend can still be labelled Expenditure because the classifier sees only the body. It does not verify whose transaction it is, insert an expense, or change totals.

## What happens for a chat message

1. Type a message in the Chat tab and tap **Send**. Blank messages cannot be sent.
2. `ChatViewModel` adds your message and starts work on a background executor.
3. `PythonBridge.replyToChat(...)` builds a versioned JSON payload and calls `orchestrator.handle_chat(...)` in the APK's Python runtime.
4. Python validates the payload and calls `app_help.get_help()` directly for local capability information. The chat body is not passed to the help function or OpenAI.
5. Kotlin checks the reply and displays it with elapsed processing time. A processing error becomes a generic retry message.

Chat keeps the latest 60 entries in memory, survives rotation and tab changes, and resets after the app process ends. It does not write chat messages to disk or logs. SMS history is stored separately and survives restarts.

## What happens for one SMS

1. Android delivers a new SMS to `SmsReceiver`.
2. Its background executor combines the SMS parts and calls shared `SmsProcessor`, which saves one local message with `Python: Pending`.
3. `PythonBridge` encodes the Python request as JSON, starts the embedded runtime if needed, and calls `orchestrator.handle_sms(...)`.
4. Python validates the fields and returns a JSON receipt.
5. Kotlin checks the receipt and updates the **same saved message** to `Python: Acknowledged`.
6. If cloud classification is enabled, enqueue a separate WorkManager job requiring internet. The receiver does not wait for inference.
7. The worker verifies permission, capture token, retained message, and current cloud consent/keys. `orchestrator.classify_sms` calls `expenditure_classifier.classify_expenditure(body, api_key, model)` directly. The classifier builds the few-shot prompt and makes one hosted request using the classification key. Credentials are separate arguments, not part of the SMS JSON; opted-in encrypted storage restores them after process restarts.
8. If the boolean is true, the worker rechecks permission, capture generation, retained attempt, and current cloud consent. It then calls `PythonBridge.identifyCategory` → `orchestrator.identify_sms_category` → `category_identifier.identify_category` for a second hosted request using the category key. Other messages or expenditure failures skip this stage.
9. Kotlin validates the category against the same vocabulary as Python and saves both results on the **same captured card**. Expenditure/Other and category failures remain distinct. A category failure never changes Expenditure to Other. The original local handoff status and timings remain separate.

The displayed receipt contains only `schema_version`, `received`, `message_id`, `character_count`, `python_version`, and `processed_at_utc`. `received: true` means Python accepted the handoff; it does **not** mean the SMS is a financial transaction. Sender and message body are not echoed in this receipt.

`Handoff time` measures the SMS bridge call, including waiting for its shared lock and Python startup when needed. It excludes SMS delivery, receiver setup, and the receiver executor. Each newly captured SMS also shows the stage breakdown described under [SMS bottleneck measurements](#sms-bottleneck-measurements). The Python function does not print, and message content is not logged.

See the [testing guide](../docs/wiki/Testing.md#what-gets-tested-in-the-normal-flow) for the chat/SMS flow, test boundaries, and runner commands. Tests are started by the developer, separately from normal app use.

## Python workflow and MCP decision

The Python package now contains eight focused modules:

- `orchestrator.py`: versioned SMS/chat entry points and the fixed processing workflow.
- `expenditure_classifier.py`: expenditure prompt construction and strict boolean parsing.
- `category_identifier.py`: category prompt construction and strict enum parsing.
- `category_examples.py`: personal category/sub-category hierarchy and 24 sanitized user-labelled examples.
- `llm_usage.py`: safe token telemetry and supported USD cost calculations with dated rates.
- `llm_api.py`: shared safe HTTPS transport and response-envelope parsing.
- `classifier_examples.py`: sanitized few-shot and test examples.
- `app_help.py`: local setup/capability text for the Chat tab.

Classification calls the helper directly; chat calls local help directly. There is no MCP client, server, handshake, tool catalogue, JSON-RPC transport, or desktop stdio endpoint in this build. The redundant tool wrapper and MCP-only tests/compatibility script were removed; their source remains in Git history.

This is still an LLM-powered orchestrator application. The Android worker sequences the two Python entry points and rechecks lifecycle/consent between uploads; Python validates input and calls each inference helper. The LLMs return decisions, not their own next actions. Structured output plus Python and Kotlin validation enforce real booleans and safe failures; MCP is not needed for that contract. Successful classification returns a correlated JSON result containing `status: "classified"`, `is_expenditure: true/false`, and `api_elapsed_ms`. A failure returns `status: "failed"` with a safe error code and no decision.

MCP is reserved for possible future chatbot expense tools once extraction, categories, and local expense storage exist. No autonomous tool-selection loop or training was added. Existing capture/consent checks and payloads remain. Category identification adds a second body upload for expenditures, updated consent text, a nested suggested-category result, and separate timings. The expenditure request schema and prediction definition remain unchanged.

## Open and build

1. In Android Studio, open this `android-companion` directory, rather than the repository root.
2. Let Gradle sync finish. Use Android Studio's configured Gradle JDK and install any requested Android SDK components. The app targets Android 17 / API 37, with Android 8 / API 26 as its minimum. This APK includes `arm64-v8a` for your Pixel 7a and `x86_64` for a 64-bit emulator.
3. Ensure Python 3.11 is available on the Windows build machine, for example through `py -3.11 --version`. Chaquopy uses it for build-time bytecode compilation; its major/minor version must match the embedded Python 3.11. No `pip install` is needed for this milestone.
4. Connect your phone and select it in Android Studio. Use **Run** to install and open the debug app. For a USB connection, enable USB debugging on your phone and approve its connection to your computer.

Run the update over your existing development installation using the same debug signing key. Its saved messages and capture setting should remain. **Do not uninstall or clear Android app storage to update:** those actions erase the local history. Messages captured before this update remain visible as `Python: Not processed`; they are not replayed automatically.

The project uses Chaquopy `17.0.0` with embedded Python `3.11`. Its generated Android Gradle plugin is `9.4.1`, newer than Chaquopy's documented tested range through `9.2.x`. The debug app and Android test APK build with these versions; the integration suites check packaged Python and the chat screen on a device. Actual SMS reception still needs the manual checks below. The [Chaquopy Gradle guide](https://chaquo.com/chaquopy/doc/current/android.html) documents runtime versions, CPU architectures, and the `buildPython` setting if local Python discovery fails.

Gradle's **configuration cache** is disabled in `gradle.properties` because Chaquopy's build-Python discovery and task serialization failed with it enabled. This is a build-tool compatibility setting, not an Android runtime setting. Normal incremental builds still work; no Android SDK/plugin downgrade was needed.

To build an APK from PowerShell instead, run these commands from the repository directory:

```powershell
cd .\android-companion
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

The `JAVA_HOME` setting applies to that PowerShell window; adjust it if Android Studio was installed elsewhere. You can also use Android Studio's Build menu to build an APK. After a successful debug build, the file is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

This is a debug APK for your personal development phone. If you transfer and install the APK manually, Android may ask you to allow installation from the app opening that file. Installation does not grant SMS access: open the companion and grant that permission through Android's normal permission dialog.

## Simulate SMS without another phone

1. Install the **debug** APK. In **SMS capture**, enable capture and grant SMS permission. The simulator deliberately keeps the existing permission/capture lifecycle checks; it is not a way to bypass them.
2. Tap **Simulate SMS (debug only)**, above the cloud controls.
3. Leave the sender as `DEMO` or edit it. Paste synthetic text in **Sample SMS body**, or choose **Load expenditure example** / **Load other example**. Sender must be nonblank and at most 100 characters; body must be nonblank and at most 12,000 UTF-16 characters. Identical submissions get distinct test IDs.
4. With cloud **off**, tap **Add test message** for a saved card and local Python acknowledgement only; no cloud job is scheduled by this submission. With cloud **on**, tap it and confirm **Submit with cloud**. This uses your configured keys/model and can incur charges (one classification call, plus a category call for expenditures). Cancel sends nothing and adds no card. Submission is blocked while cloud settings restore.
5. Close the dialog and inspect the new card below the cloud controls. It says **SIMULATED SMS - test only**, retained after restart. Expected sample labels: Blinkit payment → Expenditure, utilities/groceries; cashback advertisement → Other, category skipped. These are expectations, not guaranteed live-model results.

The shared path is `SmsProcessor → SmsStore → PythonBridge.acknowledge → optional SmsClassificationWorker → existing Python classification/category helpers`. The simulator does not call a separate fake classifier, send an SMS, broadcast `SMS_RECEIVED`, or change the phone's inbox. Pausing/clearing cancels or invalidates work as usual. Cloud consent still applies; requests already sent cannot be unsent. Reclassifying any card is a separate potentially chargeable attempt.

JSON conversion, Python startup/handoff, hosted inference, tokens and calculated costs still appear. **Simulation does not measure SMS detection, receiver setup/queueing, delivery delay, or app-entry-to-handoff time**; those fields are absent, not zero. Use occasional real SMS checks to verify reception and closed-app delivery. The simulator exists only in `app/src/debug`; `app/src/release` supplies an empty UI/rejecting stub, with no simulator implementation or extra exported component.

Test text drafts are kept in memory, not Android saved-instance state, and cleared on close/success. Saved test cards follow the same retention/clear controls as captures and do not enter a finance ledger. The only new stored metadata is an optional local `simulated` flag; older cards default to real-capture display. This flag is not sent to the LLM.

## Use it

- The app opens on **Chat**. Send `How do I use this finance tracker?` to receive built-in help through Python. Every message currently gets capability information.
- Select **SMS capture** to use the existing SMS controls and view captured messages.
- Enable capture in the app, then approve the SMS permission request.
- Send a plain SMS to your phone from another phone. Return to the companion to inspect the received entry and its Python acknowledgement.
- Pause capture to stop saving subsequent messages.
- Clear the captured history to remove the companion's copies. This does not delete messages from your phone's normal messaging app.
- You can also revoke SMS permission in Android's app settings. Return to the companion to see the updated permission status.

The app requests `RECEIVE_SMS` for Android's new-message broadcast and `INTERNET` for opt-in classification. It does not become the default messaging app, read your existing inbox, or send SMS. It does not request `READ_SMS` or `SEND_SMS`. Capture/help stay local without cloud opt-in. Message bodies are not written to app logs, and the stored history is excluded from Android backup/transfer rules.

## Set up hosted expenditure classification

1. Open **SMS capture**, enable capture, and scroll to **Cloud expenditure classification**.
2. Create two distinct OpenAI API keys, preferably named `financial-classification` and `category-identification`. Enter them privately in the matching **Financial classification API key** and **Category identification API key** fields on the phone. A key is the bearer credential that authorizes API requests; never paste it into a chat, Git, build settings, or an APK. API usage needs available API quota/billing; this app cannot verify your account's balance.
3. Leave the default model `gpt-5.6-luna` or enter a model ID your API project can access. The default supports structured outputs and reasoning effort `none`. An override must support the Responses API and the strict boolean object schema. [Model documentation](https://developers.openai.com/api/docs/models/gpt-5.6-luna).
4. Read and check the upload consent, then tap **Enable cloud classification**. It starts off and cannot be enabled without two different keys and consent. By default keys and consent are process-memory-only. Optionally select **Remember keys on this device** to encrypt them and continue background uploads after process restarts. Both masked entries are cleared after enabling and are not restored through Android saved state.
5. Send a synthetic SMS such as `Sent Rs.23.00 From HDFC Bank A/C *0000 To BMTC On 01/09/26 Ref DEMO`. Expect **Expenditure (true)**.
6. Expenditures also receive a category suggestion. For the BMTC example above, your personal prompt suggests **utilities → transport**. Send `Get up to Rs.300 cashback on your next recharge. Recharge now!`; expect **Other (false)** and **Category: Skipped**. These are manual expectations, not measured model accuracy. Amounts, bank names, and merchants alone are not evidence of a completed outgoing payment.
7. Inspect the card's classification status and timings. Use **Disable cloud and forget keys** to stop pending work and remove both in-memory and saved credentials/consent. Capture can continue locally.

Only the SMS body is sent as the new prompt input, not the sender, message ID, or timestamps. The body is sent as-is: it can include personal information, account suffixes, reference numbers, or OTPs. This POC does not redact live bodies before uploading. All new captured SMS, including ordinary/non-financial SMS, are eligible while opted in. **Use synthetic SMS for demos and disable cloud when finished.** Stored examples in the prompt/tests are sanitized versions of the supplied 25 payment and nine non-payment samples, plus synthetic credit/OTP/failed-payment counterexamples.

Enabling cloud does not upload old messages automatically. While capture/cloud are enabled, **Classify this message** / **Reclassify** schedules an explicit potentially chargeable attempt. Predictions can be confirmed/corrected and recording intent selected; no amount extraction, expense-ledger insertion or training happens yet. Old classifications are not retroactively changed by prompt updates.

This is direct personal API access for your own development phone, not a safe distribution design. Neither key is embedded, logged, saved in plaintext, or placed in WorkManager input. Opted-in keys are encrypted on disk; both still exist in client memory during use. OpenAI recommends keeping keys on a server for distributed apps. [OpenAI authentication/key guidance](https://developers.openai.com/api/reference/overview).

### Track each functionality's cost

Use each key exclusively for its matching app function. OpenAI's Costs API can filter by `api_key_ids` and group by `api_key_id` to report monetary amounts in daily buckets. The classification key is used for every eligible SMS; the category key is used only after a valid Expenditure result. Reclassification repeats the calls and can incur another charge. [Official OpenAI Costs API](https://developers.openai.com/api/reference/resources/admin/subresources/organization/subresources/usage/methods/costs).

The app has no billing viewer and does not request organization Admin credentials. Read reported costs outside the app; do not install an Admin key on the phone. This update separates authentication/cost attribution, not models, token prices, or billing accounts. Keep optional terminal evaluation on a separate evaluation key to avoid mixing its charges with app-function costs.

### Background processing and remembered credentials

The screen does not need to stay open. Android's manifest SMS receiver captures new messages and WorkManager handles the network calls. There is no permanently running Python service or foreground-service notification.

For this personal POC, enter both keys, accept upload consent, and optionally check **Remember keys on this device** before enabling cloud. This option is **off by default** and explicitly authorizes uploads of future captured SMS after process restarts. Both keys, model, consent, and the cloud-session token are encrypted with AES-256-GCM. The encryption key stays in Android Keystore; authenticated ciphertext is stored in `noBackupFilesDir/cloud_credentials.enc`. App/worker state is excluded from backup and device transfer. Keys are not bundled, logged, put in WorkManager input, or restored into text fields.

`FinanceTrackerApplication` restores remembered settings on a background thread even when an SMS/worker starts the process without an Activity. Receiver scheduling and workers wait for the same initialization. Remembered sessions survive process recreation so existing **Waiting** jobs remain eligible; re-enabling rotates the session, and disabling invalidates it. A previously **Running** attempt becomes **Interrupted**, not automatically replayed, to avoid duplicate chargeable calls. Enabling never scans/replays older history.

Without Remember selected, keys/consent remain memory-only and disappear when Android ends the process. **Disable cloud and forget keys** removes both saved credentials and upload consent and cancels pending work; capture can remain local. If encrypted settings are unreadable or the encryption key is missing, cloud fails closed with a safe error. **Forget saved keys** clears that state. No credentials or cryptographic exception contents appear in error messages.

Background work can be delayed by connectivity, battery restrictions, or Android scheduling. This works after the phone's first unlock following reboot, not before it. **Force stop**, revoked SMS permission, paused capture, disabled cloud, uninstall, or a powered-off phone prevent processing; reopen after Force stop. Encryption improves storage protection but does not protect against a compromised/rooted phone or a compromised app process. Do not use an organization Admin key here. [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent).

### History is retained, cards are paged

Captured diagnostic SMS remain until **Clear captured messages** or app-data removal. The screen initially shows 20 newest cards; **Show older messages** reveals the next 20 without uploading anything. Passing 20 messages no longer evicts old records or cancels their pending analysis. Previously evicted messages cannot be recovered; the app still does not read the SMS inbox.

The existing atomic JSON history file is retained for compatibility and POC simplicity, not replaced with an expense ledger. It loads/rewrites the history as a whole; larger archives will eventually need a database and bounded queries. SMS history itself is app-private, not additionally encrypted by the credential vault. Clear only if you intend to remove these local copies; the phone's SMS inbox is unchanged.

### Manual closed-app check

1. Enter both private keys, accept consent, check Remember, and enable capture/cloud.
2. Press Home or swipe away the screen. Send an ordinary **synthetic** payment SMS from another phone, then reopen and inspect classification, category, cost estimates, and timings.
3. Reboot, unlock the phone once, and send another synthetic SMS without first opening this app. Open it afterward and confirm the remembered cloud status and result. Scheduling may take time; this is a live check and can incur API charges.
4. Disable cloud and forget keys. Reopen/restart and confirm Cloud is Off; new SMS can still be captured locally without uploads.

Automated tests recreate isolated settings/storage instances using synthetic keys and real Android Keystore. They verify recovery contracts, not actual carrier delivery, reboot scheduling, or live model predictions. Keep those manual checks separate.

## Per-message calculated cost and bottleneck timings

Each analysed SMS card now shows API-reported input/output tokens, cached input and cache-write tokens when supplied, a calculated USD cost per stage, and a combined **Calculated cost this attempt** when every required stage has a known estimate. Other messages require only classification; expenditures include the category call. This preserves the existing receiver, JSON encoding, Python startup/handoff, separate hosted-call, and total-analysis timings. Timings do not include SMS delivery or WorkManager scheduling wait.

Costs include the whole request (instructions, examples, schema and SMS), not just the SMS text. Output usage already includes reasoning; it is not added again. The app requests `service_tier: default` and currently prices only the exact reported `gpt-5.6-luna` model on that reported tier, with rates verified **2026-10-03**. USD per million tokens: ordinary input **0.20**, cached input **0.02**, cache writes **0.25**, output **1.20**. Cache reads/writes are subtracted from total input before ordinary-input pricing. Requests above 272,000 input tokens use the documented full-request long-context premium. Rates/date are preserved with each estimate; update the pricing module when rates change. [Model/pricing documentation](https://developers.openai.com/api/docs/models/gpt-5.6-luna), [cache accounting](https://developers.openai.com/api/docs/guides/prompt-caching), [response usage fields](https://developers.openai.com/api/reference/python/resources/responses/methods/create).

Missing or malformed usage, missing cache breakdown, an unknown model or tier, and network failures show **Unavailable**, not zero. Valid predictions remain valid even when telemetry is absent. Refused/incomplete/malformed model outputs retain usage and costs if the provider supplies them, and failures now retain measured call time. A category failure never erases the expenditure label or first-stage usage.

These are calculated estimates, not provider-reported bills. They exclude account-specific adjustments, taxes and currency conversion. The latest attempt replaces the card's previous analysis; earlier retries, discarded/cancelled calls and cleared history are not a spending audit. Never assume a timeout or missing usage means the request was free. Use the two dedicated keys and OpenAI's external cost reporting to reconcile totals; the app does not store Admin credentials or fetch billing records.

For a manual check, re-enter both keys and consent, send a synthetic BMTC payment, and inspect two sets of tokens/costs plus the combined amount alongside timings. Send an advertising SMS and expect only classification cost with category skipped. Old cards still load without usage and are not uploaded automatically. Reclassifying them is an explicit new, potentially chargeable attempt.

### Classification status and timing

The classifier considers a completed outgoing purchase/payment/debit/transfer an expenditure. Promotions, loan offers, EMI-conversion offers referring to old spending, incoming credits/refunds, balance-only updates, failed/reversed payments, OTPs, and ordinary messages count as Other. This is a prompt definition, not a claim of perfect model accuracy.

| Status | Meaning |
| --- | --- |
| Not classified | No job was scheduled for this capture. |
| Waiting | WorkManager is waiting for network or system scheduling. |
| Classifying | The background attempt started. |
| Expenditure / Other | A valid actual boolean was returned by the hosted model. |
| Failed | Authentication, quota, network, refusal, malformed response, or another safe error. Never treated as Other. |
| Interrupted | Paused/disabled work or a running attempt recovered after process death. |

**Total analysis processing** measures the worker's Kotlin/Python calls, including runtime setup and both hosted stages when the SMS is an expenditure. **Expenditure API round trip and validation** measures the first call; **Category processing** and **Category API round trip and validation** measure the second stage. These include HTTP/network/model processing and parsing, not pure model compute time. They exclude WorkManager waiting and the final store/UI update. Totals contain individual stages: do not add every displayed duration together. Failure cards retain durations when available. Capture's JSON/handoff timings remain separate and exclude both hosted stages.

WorkManager requires connectivity; Android decides when work runs, so it is not an instant-delivery guarantee or a speed optimization. Jobs contain only message ID, attempt ID, capture generation, and a random cloud-session token. The worker rereads the retained message locally, verifies permission/consent/capture, and avoids duplicate pending attempts. No retry is requested for HTTP failures; manual retry may incur another charge. WorkManager can restart interrupted jobs, but only current consent/session and a still-Waiting retained record allow a new API call. A restarted process restores keys/consent only if Remember was explicitly selected; the same saved session authorizes still-Waiting jobs. Without Remember, uploads stop at process death. A running record recovers as Interrupted.

Requests use verified HTTPS, block redirects, set `store: false`, bound response size, and use a **60-second socket-operation timeout**, not a hard total deadline. `store: false` is not a claim of zero provider retention. Pause/clear/disable prevents stale writes and further retries; a sent request cannot be recalled. [Structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [Android background work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started).

### Temporary network failures and retries

The Python transport makes one request; `InferenceRetry.kt` in the Android worker owns **two retries per stage**, with one/two-second exponential backoff plus up to 250 ms jitter. Retries apply only to timeout, network, and unhinted server errors. Authentication/access/quota/rate-limit errors, invalid/refused responses and `Retry-After` hints do not retry automatically; hinted errors show a safe wait-and-retry message. Consent/session, permission, active capture generation, retained running attempt and cancellation are checked before every request and every 100 ms during backoff.

A four-minute per-stage retry window stops scheduling new attempts; it cannot impose a hard deadline on an in-flight socket operation. Exhaustion stays visibly Failed, not Other/Not sure. Category retries never rerun successful classification, and WorkManager does not add a second retry loop. Constants: `REQUEST_TIMEOUT_SECONDS = 60` in `llm_api.py`; `MAX_INFERENCE_RETRIES = 2` and `RETRY_WINDOW_MS = 240000` in `InferenceRetry.kt`. These are source settings, not phone controls. Raising them needs attention to Android worker execution limits and potentially repeated charges. [OpenAI retry guidance](https://developers.openai.com/api/docs/guides/rate-limits).

Cards show request attempts per stage. API round-trip fields and usage describe the final response; total analysis/category processing includes retry waits and calls. When a stage retries, combined cost is Unavailable: a timeout may already be billable and no complete total is known. Existing model, keys, pricing and initial-capture measurements are unchanged.

## Category identification

Category identification runs automatically after a valid Expenditure result, using the same model but its own category-identification key and a second request containing the SMS body. It runs only while permission, capture, retained attempt, and cloud consent still allow it. It does not run for Other messages or expenditure failures. This adds sequential API latency and possible charges; it is not a performance optimization.

The category prompt is in [`category_identifier.py`](app/src/main/python/category_identifier.py); labels and examples are in [`category_examples.py`](app/src/main/python/category_examples.py).

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

New results display both suggested labels on the same SMS card. Old flat suggestions stay marked as legacy, with no invented sub-category or silent upload. Reclassification is an explicit new chargeable attempt. Model, keys, gates, cost telemetry and timings are unchanged. Uncertain current pairs can be reviewed using the form below; no training or automatic prompt update occurs.

### Recording choice, category validation and editing

1. Find a completed **Expenditure** card. Its recording switch asks whether to record it, starts off, and saves inclusion intent locally. Turning it off leaves the SMS visible and editable. This does not disable capture/analysis or reverse API charges. Actual ledger writes/training are deferred; Other/failed classification has no switch. Recording choices reset on reclassification, while human labels are retained.
2. Review categories directly on the SMS card or in **Chat**, below help replies. **Not sure / Not sure** displays empty Category/Sub-category fields with examples. A predicted pair asks Yes/No: Yes saves it as confirmed; No opens the same empty correction fields. Existing saved current pairs can be reviewed without uploading again.
3. Use the displayed personal examples or your own labels. For a clothing purchase, you might choose `fun / clothing` or another category matching your finances; this is your choice, not an inferred default. The current model hierarchy does not include clothing.
4. Enter both labels and tap **Submit category**. Each trimmed label must be 1–80 characters, with no internal control characters and not `Not sure` (case-insensitive). Submission is local, offline, and free of inference charges, even with capture/cloud paused.
5. Both Chat and SMS show the confirmed pair. **Edit category** pre-fills your saved labels; **Save changes** updates them and **Cancel edit** discards changes. Reopen the app: saved labels and recording choices remain. Unsaved drafts reset; unanswered validation remains pending indefinitely.

`CategoryReview` defines the annotation and eligibility gates; `CategoryReviewCard` owns Yes/No, correction fields and editing. `ExpenditureRecordingChoice` owns the switch. MainActivity submits to `SmsStore` on IO: `confirmCategory` matches message/attempt and rejects duplicate creation; `editCategory` matches the original reviewed attempt and observed timestamp, rejecting stale editors; `setRecordExpenditure` matches the current Expenditure attempt. Optional `category_review` and `record_expenditure` fields load compatibly with old history. Neither needs internet or active capture/cloud authorization.

Annotations contain both labels, original reviewed attempt ID and latest confirmation/edit time, next to the SMS. Model prediction/usage/timings remain separate; custom human labels do not weaken its schema. Latest labels are editable, not a full audit history. Reclassification preserves human labels but resets recording intent. Clear removes both with their diagnostic copies. No expense ledger, separate training dataset, automatic learning, notification or natural-language reply parsing exists yet.

Reviewed SMS/labels can later inform sanitized few-shot examples or a separately evaluated training experiment. They are **not automatically reused** and do not retrain the model. Expand allowed prediction labels/schema before introducing custom labels into future prompts. This is a fixed human-in-the-loop workflow, not a model-directed agent tool loop.

Verified October 10 for `0.13.0-expenditure-review`: **56 Python tests and 80 Android checks passed**; debug app/test APKs, unsigned release APK, and lint passed. Updated on the Pixel 7a running Android 17 without clearing history or remembered keys. The final Android run completed in 36.363 seconds. The explicit debug-only Compose test host resolved the earlier generic-host setup failures. No automated paid API calls or actual SMS sends. Existing Expenditure cards allow free checks of the recording switch, Yes/No, correction and editing; new live predictions require paid cloud opt-in.

### Manual personal-label checks

With keys/consent enabled, send **synthetic** SMS from another phone:

| SMS body | Expected personal suggestion (requires live verification) |
| --- | --- |
| `Spent Rs.100 at OPENAI. Ref DEMO` | learning → engineering |
| `Sent Rs.10 to BMTC. Ref DEMO` | utilities → transport |
| `Sent Rs.10 to Indiqube SpacesExternal S. Ref DEMO` | utilities → food |
| `Spent Rs.100 at SWIGGY PVT LTD FOOD1. Ref DEMO` | fun → food |
| `Spent Rs.100 at RAZ*Toyloft. Ref DEMO` | fun → gift |
| `Spent Rs.100 at Raz*Airtel. Ref DEMO` | utilities → prepaid recharge |
| `Spent Rs.100 at BLINKIT. Ref DEMO` | utilities → groceries |
| `Spent Rs.100 at BLINKIT for medicines. Ref DEMO` | utilities → health (explicit purpose overrides merchant) |
| `Sent Rs.10 to Unknown Person. Ref DEMO` | Not sure → Not sure |
| `Get cashback on your next recharge!` | Other; category stage skipped |

Compare predictions with your intended labels; offline tests do not establish live model accuracy. Category failures preserve Expenditure and do not create review forms. No expense ledger or model training is included yet.

## Automated checks

The [Markdown testing wiki](../docs/wiki/Testing.md) provides a detailed suite-by-suite guide, commands, timing boundaries, coverage limits, and troubleshooting.

There are 56 Python unit tests and 80 Android checks across seventeen classes. Added coverage includes repayment prompt rules, network retry limits/cancellation/cost uncertainty, recording choices, Yes/No validation, correction and stale-safe editing. Inputs/storage/credentials are synthetic or isolated; HTTP is mocked/blocked or uses a missing key. No paid calls, inbox reads, actual SMS sends, or real-history clearing occurs. Tests do not establish model accuracy.

### Python unit tests on Windows

From the repository directory, activate a working Python environment and run:

```powershell
.\.venv\Scripts\Activate.ps1
cd .\android-companion
python -m unittest discover -s python-tests -p "test_*.py" -v
```

These tests require only the standard library. If your existing environment is missing or broken, create a separate test environment with an installed Python 3.11:

```powershell
py -3.11 -m venv .venv-tests
.\.venv-tests\Scripts\Activate.ps1
python -m unittest discover -s python-tests -p "test_*.py" -v
```

Run that second block from `android-companion`; ensure `py -3.11 --version` works first. The repository's `.venv` ignore rule excludes environments named `.venv`; this companion also ignores `.venv-tests`.

The test runner imports the same production Python that goes into the APK. Nine SMS tests verify receipts, required fields, invalid input, Unicode, timestamps, and safe output. Four chat tests verify offline help replies and errors. Sixteen classifier tests check the few-shot schema, real booleans, direct-call result/timing validation, the 34 original sanitized cases plus three repayment negatives, refusal/incomplete responses, safe errors, timeout/retry-deferral settings, missing credentials, limits, and blocked redirects. Seventeen category tests check both enums, all 24 supplied labels with bank identifiers sanitized, allowed-pair relationships, uncertainty/purpose rules, parser, transport/errors, correlated results, and failure contracts. Ten usage tests check token/cost telemetry without paid calls. Each `test_...` method runs independently; `subTest` checks variants. Mocking the provider proves the workflow handles labels correctly, not that a live model predicts them correctly. Android checks verify the packaged Python boundary separately.

The unit suite uses only the standard library. There are no MCP protocol tests or SDK compatibility dependencies in the current implementation.

For an **optional paid model evaluation**, set `OPENAI_API_KEY` privately in your terminal environment (do not commit it or put the literal key in shared instructions), then run from `android-companion`:

```powershell
python python-tests/evaluate_classifier.py
```

This explicitly sends sanitized cases not present verbatim in the prompt, reports correct predictions/request failures and API latency, and stops early for authentication/access/quota/request-configuration failures. Repeated bank templates are a narrow evaluation, not broad financial-message accuracy. `OPENAI_CLASSIFIER_MODEL` can override the default. This script is never run by unittest discovery and is not an Android delivery/worker test.

For an optional performance measurement:

```powershell
python python-tests/benchmark_handoff.py
```

This reports median and 95th-percentile processing time for 100 calls after warm-up. It measures host Python only, excluding interpreter startup, Android, the bridge, and SMS delivery. Compare runs on the same machine; timings have no pass/fail threshold.

### Android integration tests

The UI tests use Espresso `3.7.0`, AndroidX JUnit `1.3.0`, and the test runner `1.7.0`. The template's Espresso `3.5.1` failed on Android 17 while reflecting on `InputManager.getInstance`; [Espresso 3.7.0's release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0) document the change to use `getSystemService` instead.

1. Open any of the seventeen classes under `app/src/androidTest/java/com/abijith/expensetracker/`. New classes are `InferenceRetryInstrumentedTest` and `RecordingChoiceInstrumentedTest`; review store/UI classes now also test confirmation and edits.
2. Connect your Pixel or start a supported emulator.
3. Click the green run arrow beside the test class and choose **Run**. Android Studio installs the app and a separate test APK, then shows the results.

The four bridge tests call embedded Python with synthetic SMS/chat messages. They check acknowledgements, emoji counting, capability replies, and safe failures. The screen test enters a chat message, sends it, checks Python's reply, and switches between tabs. SMS permission is unnecessary because the tests do not inject SMS broadcasts. The successful SMS handoff time appears in Logcat under `PythonBridgeTest`; it may include Python startup, depending on which test ran first.

Verification on October 2, 2026: all 26 Python unit tests passed in the project's Windows Python 3.11.9 environment, and all 14 Android checks passed on the Pixel 7a running Android 17. The debug app/test APK builds and Android lint passed; the updated APK was installed over the existing app without clearing captured history. No paid API calls were made during those automated checks. On October 3, the user reported successful live classification on the phone; no counted accuracy evaluation or live latency results were supplied. A broader evaluation can use the optional paid script or more phone checks. The optional host timing command still benchmarks `handle_sms`, not chat or hosted classification.

Direct-call update verification on October 3: **27 Python tests and 14 Android checks passed**, and the debug app/test APK builds and Android lint passed for version `0.6.0-direct-llm-classification`. The app and test APKs were installed on the Pixel 7a running Android 17 without clearing storage; all six classes passed through AndroidJUnitRunner. No paid API calls were made. The updated direct-call path still needs a live synthetic SMS check. Reopen the app and re-enter the memory-only key/consent after the test process restart.

Per-message cost update verification on October 3: **51 Python tests and 29 Android checks passed**; debug app/test APK builds and lint passed. Version `0.8.0-message-costs` was installed on the Pixel 7a running Android 17 without clearing storage. No paid API calls were made. Live category predictions still need your manual synthetic SMS check; re-enter both process-only keys and consent after test/install restarts.

Background/history update verification on October 3: **51 Python tests and 39 Android checks passed**; app/test builds and lint passed. Version `0.9.0-background-processing` was installed on the Pixel 7a without clearing history. Recovery tests use isolated ciphertext files and Keystore aliases, not the user's credentials. No paid calls were made. Live closed-app/reboot SMS checks remain manual.

Personal category update verification: **54 Python tests and 42 Android checks passed**, plus debug app/test builds and lint. Version `0.10.0-personal-categories` was installed without clearing captures or remembered credentials. All model replies in automated tests were mocked; live predictions still require the synthetic checks above.

Simulator update verification on October 4: **54 Python tests and 53 Android checks passed**, plus debug app/test and unsigned release builds and lint. Version `0.11.0-sms-simulator` was installed on the Pixel without clearing captures or remembered credentials. No SMS or paid API calls were sent by the tests. Shared local submission works; actual cloud predictions remain a manual opt-in check.

To run all seventeen application test classes from PowerShell, from `android-companion`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:connectedDebugAndroidTest
```

Use a development device/emulator: Android test tooling installs development APKs and may restart the app. These tests cover the bridges, basic chat/consent controls, and isolated classification-store recovery. Actual SMS delivery, permission dialogs, end-to-end WorkManager scheduling, cloud inference, capture controls, and usability still need manual checks. Android Studio's generated sample tests have been removed; only tests of the actual implementation remain. GitHub Actions is not configured yet; these suites run locally.

## SMS bottleneck measurements

The measurement starts at the first statements in `SmsReceiver.onReceive`, when this app observes Android's SMS broadcast. It does not measure when the modem received the SMS or the time spent creating the app process before `onReceive` runs.

Android's [SMS timestamp](https://developer.android.com/reference/android/telephony/SmsMessage#getTimestampMillis()) is a service-centre timestamp, not a local receipt timestamp. The displayed **SMS timestamp to app entry** subtracts it from the phone's wall clock. That gap includes delivery, Android scheduling, and clock differences; it is an estimate, can be negative, and must not be reported as pure app detection latency. All processing durations use [elapsedRealtimeNanos](https://developer.android.com/reference/android/os/SystemClock), a monotonic clock that includes sleep, and are displayed in milliseconds with three decimal places.

| Display / log field | What it measures |
| --- | --- |
| App entry to JSON ready / `receiver_to_json_ready_ms` | Receiver entry through setup, executor wait, preparation, initial save, bridge wait, and completion of the Python request JSON. |
| JSON conversion for Python / `json_encode_ms` | Only construction and serialization of the versioned SMS payload by `SmsPayload.encode`. It excludes Python startup and the capture-history file's JSON serialization. |
| Receiver setup / `receiver_setup_ms` | Permission/capture checks, opening local capture state when necessary, and scheduling work. |
| Executor wait / `executor_wait_ms` | Waiting for the receiver's single-thread executor to begin this message. |
| Message preparation / `message_preparation_ms` | Decoding/combining SMS parts, checking the body, generating its ID, and constructing the captured record. |
| Initial save / `initial_save_ms` | Persisting the Pending message, including serializing local history and writing the file. |
| Bridge wait / `bridge_wait_ms` | Waiting for the shared Kotlin/Python lock, which may be held by chat. |
| Python setup / `python_setup_ms` | Runtime readiness check and startup if needed. `runtime_started` identifies calls that actually started Python. |
| Python call / `python_call_ms` | Module lookup, Kotlin-to-Python call, Python processing, and returning the receipt string. |
| Receipt validation / `receipt_validation_ms` | Parsing/checking the result and constructing the safe acknowledgement. |
| App entry to handoff result / `receiver_to_handoff_ms` | Receiver entry until the bridge captures its final status/timings. Excludes the final history update and UI rendering. |

The two **App entry to...** values and `bridge_total_ms` are totals containing several stages; do not add them to individual stage durations. Timing probes and normal device activity add overhead. Use repeat measurements to compare changes, not a strict threshold for one sample.

To collect real receiver timings, enable capture and send a new, ordinary synthetic SMS to the Pixel. Select **SMS capture** and inspect **Processing timings** on that message. The timings are persisted with its handoff and remain after reopening the app; older captures have no retroactive timing data. In Android Studio Logcat, filter by `SmsPerformance` for the corresponding timings and hashed message ID. These logs contain no sender or body.

The automated `SmsPerformanceInstrumentedTest.kt` provides three checks without SMS permission or modifying captured history:

1. **JSON serialization benchmark:** 20 warm-ups, then 100 measurements each for short (160 characters), long (2,000 characters), and Unicode (240 code points) messages. It uses the exact production serializer and checks JSON round-tripping outside the measured interval.
2. **Warm bridge benchmark:** one observed call, then 100 calls with the runtime already started, reporting each bridge stage's median and nearest-rank 95th percentile. The first observed call is a cold Python start only if `runtime_started` is true; test order can otherwise leave Python warm.
3. **Timing persistence format:** verifies precision, optional fields, and signed clock estimates survive JSON round-tripping. This checks the timing format, not the full capture store or actual Android broadcasts.

Run that class using its green arrow in Android Studio. Filter Logcat by `SmsPerformanceTest` to see the benchmark numbers. Assertions check valid output and non-negative finite durations; there is no pass/fail speed threshold. Synthetic benchmark calls cannot measure actual SMS detection or receiver/storage timings; those require the real-message check above.

Example device results from October 2, 2026 (Pixel 7a, Android 17, debug APK, 100 samples per row):

| Measurement | Median (ms) | 95th percentile (ms) |
| --- | ---: | ---: |
| JSON encoding: 160-character message | 0.164 | 0.201 |
| JSON encoding: 2,000-character message | 0.080 | 0.943 |
| JSON encoding: 240-code-point Unicode message | 0.032 | 0.039 |
| Warm Python call, including bridge crossing | 0.195 | 0.240 |
| Complete warm bridge handoff | 0.294 | 0.358 |

The first bridge call in that separate timing-build performance-suite run actually started Python: startup took 345.616 ms and the complete bridge call took 359.473 ms. That is one cold-runtime observation, not a startup percentile or a measurement of the later classification build. Sequential JSON cases are affected by warm-up/device activity. These measurements exclude real SMS delivery, receiver preparation, storage, and the separate hosted LLM classification.

## Manual checks on your Pixel 7a

Use synthetic messages that contain no personal details or verification codes.

1. Install the updated APK using Android Studio **Run**. Send a message in **Chat**, check the local help reply, then select **SMS capture** and check that it says `Milestone 9: personal categories and sub-categories`. Leave cloud off for the capture-only checks.
2. Enable capture and grant SMS permission if needed.
3. Send `Demo purchase: Rs 120 at Cafe Example.` as an ordinary SMS from another phone.
4. Check that one message card shows `Python: Acknowledged`, a handoff time, and a receipt with `received: true` and `python_version` starting with `3.11`. `Pending` may disappear too quickly to observe.
5. Send a second ordinary SMS containing Unicode, such as `Demo grocery purchase ₹120 🛒`. Check that it is also acknowledged. Python counts Unicode code points; an emoji can occupy more than one Kotlin UTF-16 character, so the bridge compares code-point counts.
6. Press Home, send a third SMS, and reopen the app. Check that the background handoff completed. No laptop service or backend connection is involved.

These manual checks complement the automated Python and bridge tests above.

| Check | What to do | Expected result |
|---|---|---|
| Capture starts off | Open the app after a fresh installation and send a plain SMS. | No message is captured until you enable capture. |
| Permission denied | Enable capture, but deny the permission request. | Capture stays unavailable and the app remains usable. |
| Short SMS | Send the example above while capture is enabled. | One entry shows the message, timestamps, and Python acknowledgement. |
| Long SMS | Send a plain SMS long enough to be split into multiple SMS parts. | One complete message and one acknowledgement appear. |
| Background reception | Press Home, send another SMS, and reopen the companion. | The new message and acknowledgement are in local history. Do not force-stop the app for this check. |
| Local retention | Close and reopen the screen; optionally send more than 20 messages. | History and completed handoffs survive reopening; all captures remain until cleared; Show older messages reveals further pages. |
| Clear history | Clear the companion's history. | Its list becomes empty; the normal messaging app is unchanged. |
| Pause | Pause capture and send another SMS. | No new entry is added. |
| Revoke permission | Revoke SMS permission in Android settings, then return and send a message. | Status reflects missing permission and no new entry is captured. |

## Handoff status and recovery

| Status | Meaning |
|---|---|
| `Pending` | The SMS has been saved; the local Python handoff has not completed. |
| `Acknowledged` | Python returned a receipt and Kotlin validated it. |
| `Failed` | Python startup, execution, or receipt validation failed. The SMS remains saved. |
| `Interrupted or paused` | Capture was paused during a pending handoff, or a pending record was recovered after an app-process restart. The SMS remains saved. |
| `Not processed` | This message was captured before the Python update. |

The local capture acknowledgement has no automatic replay. After troubleshooting a failed/interrupted local handoff, send a **new** synthetic SMS. Hosted classification separately uses WorkManager and an explicit per-card retry; reopening the screen or enabling cloud alone does not upload old history. Clearing history invalidates pending updates, so a late result cannot restore a cleared message. Pausing discards in-flight results; it cannot immediately terminate an already-running Python call or recall an API request.

## Understand the boundaries

Android wakes the broadcast receiver for a new SMS; this milestone does not run a continuous background service. The short offline acknowledgement runs on an executor within the receiver's limited `goAsync()` window. Hosted inference runs separately in WorkManager, rather than extending the receiver call.

Force-stopping the app is different from pressing Home: open it again before checking reception. Messages received while capture is paused or permission is missing are not imported later.

RCS/chat messages, WhatsApp messages, and email are not SMS and are outside this milestone. Make sure the sending phone uses SMS for your check.

Android 17 protects some messages containing one-time passwords and can delay their delivery to apps like this. Use ordinary synthetic SMS messages when checking reception. See [Android's SMS broadcast reference](https://developer.android.com/reference/android/provider/Telephony.Sms.Intents#SMS_RECEIVED_ACTION) and [Android 17 OTP protections](https://developer.android.com/about/versions/17/behavior-changes-17).

## Code map

These files are under `app/src/main/java/com/abijith/expensetracker/`:

| File | Responsibility |
|---|---|
| `MainActivity.kt` | Chat/SMS tab navigation, capture controls, permission request/status, and SMS receipt/timing display. |
| `ChatScreen.kt` | Chat message list, input, Send button, and reply/timing display. |
| `CategoryReview.kt`, `CategoryReviewCard.kt` | Separate human annotation, predicted Yes/No, uncertain/correction fields, examples, editable labels and save/retry UI. |
| `ExpenditureRecordingChoice.kt`, `InferenceRetry.kt` | Local inclusion switch; bounded per-stage retry policy, lifecycle checks during waits and no nested retry loop. |
| `ChatViewModel.kt` | Session conversation state, background chat processing, and rotation-safe state. |
| `SmsReceiver.kt` | Receives/combines SMS parts, records reception timing, and calls the shared processor on its executor. |
| `SmsProcessor.kt` | Shared off-main-thread save/handoff/scheduling path for real SMS and debug simulation; guards permission and pause/clear tokens. |
| `PythonBridge.kt` | Starts embedded Python, calls capture/chat/classification entry points, validates safe results, and keeps HTTP outside the shared capture/chat monitor. |
| `SmsPayload.kt` | Builds and serializes the SMS request sent to Python; used by the production bridge and JSON benchmark. |
| `SmsPerformance.kt` | Monotonic timing helpers and the persisted stage-timing format. |
| `CloudClassificationSettings.kt` | Memory-only or opted-in encrypted key/consent/model lifecycle; session restoration and invalidation. |
| `CloudCredentialVault.kt` | AES-256-GCM encrypted credential file with an Android Keystore key, excluded from backup. |
| `FinanceTrackerApplication.kt` | Off-main-thread settings restoration for Activity, receiver, and worker process starts. |
| `CloudClassificationControls.kt` | Masked key entry, upload consent, enable/disable controls, classification status and timing display. |
| `SmsClassification.kt` | Persisted safe classification statuses/timings and fixed error messages. |
| `SmsClassificationWorker.kt` | Network-constrained expenditure/category stages, consent/permission checks between uploads, scheduling, cancellation, and guarded result updates. |
| `SmsCategory.kt` | Personal pair hierarchy, strict live/result validation, legacy flat-card compatibility, and the expenditure-only next-stage gate. |
| `SmsStore.kt` | Atomically stores capture state and retained history in `noBackupFilesDir`; updates handoff results on the existing message and prevents stale updates after pause/clear. |

The Python entry point is [`orchestrator.py`](app/src/main/python/orchestrator.py). `handle_sms` returns the local receipt; `handle_chat` calls [`app_help.get_help()`](app/src/main/python/app_help.py). The worker calls `classify_sms` → [`expenditure_classifier.classify_expenditure()`](app/src/main/python/expenditure_classifier.py), then only for expenditures calls `identify_sms_category` → [`category_identifier.identify_category()`](app/src/main/python/category_identifier.py). [`llm_api.py`](app/src/main/python/llm_api.py) shares the safe transport and envelope parser; [`classifier_examples.py`](app/src/main/python/classifier_examples.py) and [`category_examples.py`](app/src/main/python/category_examples.py) contain the respective prompt examples. No expense ledger or training is implemented. See the [code map](#code-map).
