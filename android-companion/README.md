# Android app — milestone 4: expenditure classification

The Android application provides its own Chat tab alongside SMS capture diagnostics, stage timings, and optional hosted expenditure classification. Python runs inside the APK. App version: `0.4.0-expenditure-classification` (`versionCode` 5).

Chat currently returns built-in help for every message, including what the app can do and which finance features are planned. Chat does not yet perform LLM inference, call MCP tools, record expenses, or answer spending questions. SMS classification can call OpenAI after you enter your own key and consent. The app runs without a WhatsApp account, Twilio, ngrok, or a separately hosted application backend.

Capture starts **off** on a fresh installation. When you enable it and grant Android's SMS permission, the app keeps the latest 20 newly received SMS messages in app-private local storage. Each entry contains the sender, body, SMS timestamp, capture timestamp, handoff status, and any classification result. All ordinary SMS messages are captured while enabled, including non-financial messages.

The capture/chat entry points validate input and return a receipt or built-in help without network calls. A separate background classification entry point uses Python's standard-library HTTPS client to call the OpenAI Responses API. No extra Python packages or separate phone Python installation are needed. Categories, MCP, and finance-database updates are not implemented in the APK yet.

Progress on October 3, 2026: the user reports that live phone classification is working and correctly predicts the messages tested. This is a manual sample check, not a measured accuracy percentage. Sender/source validation and ownership confirmation are intentionally deferred: a transaction alert copied by a friend can still be labelled Expenditure because the classifier sees only the body. It does not verify whose transaction it is, insert an expense, or change totals. See the [progress and scope decision](../README.md#progress--october-3-2026).

## What happens for a chat message

1. Type a message in the Chat tab and tap **Send**. Blank messages cannot be sent.
2. `ChatViewModel` adds your message and starts work on a background executor.
3. `PythonBridge.replyToChat(...)` builds a versioned JSON payload and calls `orchestrator.handle_chat(...)` in the APK's Python runtime.
4. Python validates the payload and returns built-in capability information.
5. Kotlin checks the reply and displays it with elapsed processing time. A processing error becomes a generic retry message.

Chat keeps the latest 60 entries in memory, survives rotation and tab changes, and resets after the app process ends. It does not write chat messages to disk or logs. SMS history is stored separately and survives restarts.

## What happens for one SMS

1. Android delivers a new SMS to `SmsReceiver`.
2. Its background executor combines the SMS parts and saves one local message with `Python: Pending`.
3. `PythonBridge` encodes the Python request as JSON, starts the embedded runtime if needed, and calls `orchestrator.handle_sms(...)`.
4. Python validates the fields and returns a JSON receipt.
5. Kotlin checks the receipt and updates the **same saved message** to `Python: Acknowledged`.
6. If cloud classification is enabled, enqueue a separate WorkManager job requiring internet. The receiver does not wait for inference.
7. The worker verifies permission, capture token, retained message, and current cloud consent/key. Python sends only the SMS body in a few-shot prompt to OpenAI.
8. A strict boolean response becomes `Expenditure (true)` or `Other (false)` on the same saved card. Failures have their own status and safe error message. The original local handoff status and timings remain separate.

The displayed receipt contains only `schema_version`, `received`, `message_id`, `character_count`, `python_version`, and `processed_at_utc`. `received: true` means Python accepted the handoff; it does **not** mean the SMS is a financial transaction. Sender and message body are not echoed in this receipt.

`Handoff time` measures the SMS bridge call, including waiting for its shared lock and Python startup when needed. It excludes SMS delivery, receiver setup, and the receiver executor. Each newly captured SMS also shows the stage breakdown described under [SMS bottleneck measurements](#sms-bottleneck-measurements). The Python function does not print, and message content is not logged.

See the repository README's [current implementation flow](../README.md#current-implementation-sms-and-app-chat) for a diagram showing where the tests enter the chat and SMS flows, and [test execution diagram](../README.md#how-you-run-the-checks) for how the runners execute them. Tests are started by the developer, separately from normal app use.

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
2. Enter your OpenAI API key privately on the phone. A key is the bearer credential that authorizes API requests; never paste it into a chat, Git, build settings, or an APK. API usage needs available API quota/billing; this app cannot verify your account's balance.
3. Leave the default model `gpt-5.6-luna` or enter a model ID your API project can access. The default supports structured outputs and reasoning effort `none`. An override must support the Responses API and the strict boolean object schema. [Model documentation](https://developers.openai.com/api/docs/models/gpt-5.6-luna).
4. Read and check the upload consent, then tap **Enable cloud classification**. It starts off and cannot be enabled without a key and consent. The key and consent are held only in phone process memory; a process restart requires entering the key again. The masked entry is cleared after enabling and is not restored through Android saved state.
5. Send a synthetic SMS such as `Sent Rs.23.00 From HDFC Bank A/C *0000 To BMTC On 01/09/26 Ref DEMO`. Expect **Expenditure (true)**.
6. Send `Get up to Rs.300 cashback on your next recharge. Recharge now!`. Expect **Other (false)**. Amounts, bank names, and merchants alone are not evidence of a completed outgoing payment.
7. Inspect the card's classification status and timings. Use **Disable cloud classification** to stop pending work and forget the in-memory key. Capture can continue locally.

Only the SMS body is sent as the new prompt input, not the sender, message ID, or timestamps. The body is sent as-is: it can include personal information, account suffixes, reference numbers, or OTPs. This POC does not redact live bodies before uploading. All new captured SMS, including ordinary/non-financial SMS, are eligible while opted in. **Use synthetic SMS for demos and disable cloud when finished.** Stored examples in the prompt/tests are sanitized versions of the supplied 25 payment and nine non-payment samples, plus synthetic credit/OTP/failed-payment counterexamples.

Enabling cloud does not retroactively upload saved messages. While capture and cloud are enabled, a saved card's **Classify this message (uploads body)** / **Reclassify (uploads body)** button schedules an explicit attempt. Results are diagnostic only; no amount extraction, category, expense insertion, or training happens yet.

This is direct personal API access for your own development phone, not a safe distribution design. The key is never embedded, logged, stored on disk, or placed in WorkManager input. It still exists in client memory during use. OpenAI recommends keeping keys on a server for distributed apps. [OpenAI authentication/key guidance](https://developers.openai.com/api/reference/overview).

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

**Classification processing** measures the worker's Kotlin/Python call, including runtime setup if necessary and the hosted call. **Hosted API round trip and validation** includes HTTP/network/model processing and parsing; it is not pure model compute time. Both exclude the wait for WorkManager and the final store/UI update. Failure cards retain the attempt duration when available. Capture's JSON and handoff timings remain separate and exclude classification.

WorkManager requires connectivity; Android decides when work runs, so it is not an instant-delivery guarantee or a speed optimization. Jobs contain only message ID, attempt ID, capture generation, and a random cloud-session token. The worker rereads the retained message locally, verifies permission/consent/capture, and avoids duplicate pending attempts. No retry is requested for HTTP failures; manual retry may incur another charge. WorkManager can restart interrupted jobs, but only current consent/session and a still-Waiting retained record allow a new API call. A restarted process has no key, so it cannot silently upload. A running record recovers as Interrupted.

Requests use verified HTTPS to a fixed endpoint, block redirects, set `store: false`, bound response size, and use a 20-second socket-operation timeout, not a hard total deadline. `store: false` is not a claim of zero provider retention. Pausing, clearing, or disabling prevents stale results from restoring records and cancels pending work; requests already sent cannot be recalled. The background Python network call may finish after cancellation, but its result is discarded. [Structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [Android background work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started).

## Automated checks

There are 26 Python unit tests on Windows and 14 Android checks: four bridge tests, one chat screen test, three performance checks, three classifier-contract checks, two isolated-store checks, and one consent-controls check. All input messages are synthetic. Offline tests mock API responses or use a missing key; they make no real API requests and prove no model accuracy. Tests do not read your inbox, send SMS, or clear the app's saved capture history. Storage tests use a separate synthetic cache directory, and consent tests use stub callbacks.

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

The test runner imports the same production Python that goes into the APK. Nine SMS tests verify receipts, required fields, invalid input, Unicode, timestamps, and safe output. Four chat tests verify help replies and errors. Thirteen classifier tests check the few-shot schema, real booleans, mocked requests across all 34 sanitized cases, refusal/incomplete responses, safe errors, missing credentials, limits, and blocked redirects. Each `test_...` method runs independently; `subTest` checks variants. Mocking the provider proves the workflow handles labels correctly, not that a live model predicts them correctly. Android checks verify the packaged Python boundary separately.

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

1. Open one of the six test classes under `app/src/androidTest/java/com/abijith/expensetracker/` in Android Studio: `PythonBridgeInstrumentedTest`, `ChatScreenInstrumentedTest`, `SmsPerformanceInstrumentedTest`, `ClassifierInstrumentedTest`, `ClassificationStoreInstrumentedTest`, or `CloudControlsInstrumentedTest`.
2. Connect your Pixel or start a supported emulator.
3. Click the green run arrow beside the test class and choose **Run**. Android Studio installs the app and a separate test APK, then shows the results.

The four bridge tests call embedded Python with synthetic SMS/chat messages. They check acknowledgements, emoji counting, capability replies, and safe failures. The screen test enters a chat message, sends it, checks Python's reply, and switches between tabs. SMS permission is unnecessary because the tests do not inject SMS broadcasts. The successful SMS handoff time appears in Logcat under `PythonBridgeTest`; it may include Python startup, depending on which test ran first.

Verification on October 2, 2026: all 26 Python unit tests passed in the project's Windows Python 3.11.9 environment, and all 14 Android checks passed on the Pixel 7a running Android 17. The debug app/test APK builds and Android lint passed; the updated APK was installed over the existing app without clearing captured history. No paid API calls were made during those automated checks. On October 3, the user reported successful live classification on the phone; no counted accuracy evaluation or live latency results were supplied. A broader evaluation can use the optional paid script or more phone checks. The optional host timing command still benchmarks `handle_sms`, not chat or hosted classification.

To run all six application test classes from PowerShell, from `android-companion`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.abijith.expensetracker.PythonBridgeInstrumentedTest,com.abijith.expensetracker.ChatScreenInstrumentedTest,com.abijith.expensetracker.SmsPerformanceInstrumentedTest,com.abijith.expensetracker.ClassifierInstrumentedTest,com.abijith.expensetracker.ClassificationStoreInstrumentedTest,com.abijith.expensetracker.CloudControlsInstrumentedTest'
```

Use a development device/emulator: Android test tooling installs development APKs and may restart the app. These tests cover the bridges, basic chat/consent controls, and isolated classification-store recovery. Actual SMS delivery, permission dialogs, end-to-end WorkManager scheduling, cloud inference, capture controls, and usability still need manual checks. Android Studio's generated sample tests are retained locally but excluded from Git; they do not verify app behavior. GitHub Actions is not configured yet; these suites run locally.

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

1. Install the updated APK using Android Studio **Run**. Send a message in **Chat**, check the help reply, then select **SMS capture** and check that it says `Milestone 4: expenditure classification`. Leave cloud off for the capture-only checks.
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
| Local retention | Close and reopen the screen; optionally send more than 20 messages. | History and completed handoffs survive reopening; at most the latest 20 entries remain. |
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
| `ChatViewModel.kt` | Session conversation state, background chat processing, and rotation-safe state. |
| `SmsReceiver.kt` | Receives/combines SMS parts, saves the message, performs a short local handoff, and enqueues cloud work only when enabled. |
| `PythonBridge.kt` | Starts embedded Python, calls capture/chat/classification entry points, validates safe results, and keeps HTTP outside the shared capture/chat monitor. |
| `SmsPayload.kt` | Builds and serializes the SMS request sent to Python; used by the production bridge and JSON benchmark. |
| `SmsPerformance.kt` | Monotonic timing helpers and the persisted stage-timing format. |
| `CloudClassificationSettings.kt` | Process-only key/consent/model with random session tokens; no secrets on disk. |
| `CloudClassificationControls.kt` | Masked key entry, upload consent, enable/disable controls, classification status and timing display. |
| `SmsClassification.kt` | Persisted safe classification statuses/timings and fixed error messages. |
| `SmsClassificationWorker.kt` | Network-constrained background inference, consent/permission checks, scheduling, cancellation, and guarded result updates. |
| `SmsStore.kt` | Atomically stores capture state and bounded history in `noBackupFilesDir`; updates handoff results on the existing message and prevents stale updates after pause/clear. |

The Python entry point is [`orchestrator.py`](app/src/main/python/orchestrator.py). `handle_sms(payload_json)` returns the local receipt; `handle_chat(payload_json)` returns local help; `classify_sms(payload_json, api_key, model)` invokes the hosted classifier. [`expenditure_classifier.py`](app/src/main/python/expenditure_classifier.py) builds the prompt/schema, sends one HTTPS request, and validates the boolean. [`classifier_examples.py`](app/src/main/python/classifier_examples.py) contains sanitized labelled examples. None records expenses or trains a model yet.
