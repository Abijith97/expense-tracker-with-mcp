# Testing the Android AI Expense Tracker

This guide explains the POC's test suites, their code locations, how to run them on Windows, and what the results mean. Tests are developer-run checks; they do not run automatically when an SMS arrives.

This is repository-hosted Markdown wiki documentation under `docs/wiki/`, not a page in GitHub's separate Wiki tab. The private repository's Wiki feature could not be enabled; keeping the documentation here preserves repository privacy and versions it alongside the code.

## Scope and current verification

Last verified: **October 4, 2026**. The current build is `0.11.0-sms-simulator`. Python tests, debug/release APK builds, and lint were verified on Windows with Python 3.11.9. All thirteen Android test classes passed on the Pixel 7a running Android 17 through AndroidJUnitRunner after installing the debug app/test APKs without clearing storage. The release variant contains only an empty simulator UI and rejecting stub, not the debug simulator implementation.

| Check | Last verified result | What it establishes |
| --- | --- | --- |
| Python unit suite | 54 tests passed | Python input/output contracts, strict boolean/category parsing, safe token/cost telemetry, shared transport safety, and mocked inference. |
| Android integration/UI suite | 53 tests passed | Packaged Python, routing, consent/cost UI, usage totals, isolated encrypted recovery, retained/paged history, shared processing, simulator input/charge confirmation, and timing contracts work on the phone. This does not verify live SMS delivery or hosted predictions. |
| Debug application/test and unsigned release APK builds | Passed | Both source-set variants build with embedded Python; the simulator implementation is debug-only. |
| Android lint | Passed | No lint errors and no remaining unused-resource warnings. Other warnings remain; this is not a claim of zero warnings. |
| Live category suggestions | Needs a manual synthetic SMS check | Automated checks do not contact OpenAI with a valid key or establish category accuracy. |

**Source version:** this guide accompanies `0.11.0-sms-simulator` with 54 Python and 53 Android checks. Commit `9e52fb4` contains the prior personal-category/background build with 54/42 checks. Commit `79704f8` contains the prior two-key/category build with 41/24 checks. Commit `c55c370` contains the prior direct-classification build with 27/14 checks. The older MCP experiment in `22b14ee` had 43/16 checks; its MCP-only files were intentionally removed. Use the matching source version when comparing test totals.

There is no configured GitHub Actions workflow. A Git push does not currently execute these tests. Android Studio and Gradle are test runners; GitHub Actions could automate selected checks later.

The `v0.11.0` tag identifies this SMS-simulator milestone (`versionCode` 13, app version `0.11.0-sms-simulator`). A short [test README](../../android-companion/python-tests/README.md) provides the quick-start commands; this guide remains the detailed coverage reference. A Git tag is a source checkpoint, not an APK distribution or a GitHub Release.

## What gets tested in the normal flow

For an SMS, Android captures and saves the message, calls embedded Python for a short acknowledgement, and optionally schedules a separate cloud-classification worker. That worker calls `orchestrator.classify_sms` for the boolean check. The bridge supplies a dedicated classification key. Only an Expenditure result with current permission/capture/consent enters `orchestrator.identify_sms_category` for the second hosted call, using its own category key. Kotlin validates each result and updates the same captured-message card. A category failure must preserve the expenditure label.

For chat, Kotlin calls the Python orchestrator, which calls `app_help.get_help()` directly. Every valid chat message currently receives capability information; spending queries are not implemented yet.

The debug **Simulate SMS** dialog enters the same shared `SmsProcessor` after reception/part decoding. It creates a labelled local card, calls the packaged Python acknowledgement, and optionally schedules the same cloud worker after charge confirmation. A cloud-off submission explicitly forbids cloud scheduling even if settings change while the local handoff runs. Permission/capture checks still apply. Simulator tests use isolated storage, a wrapper-scoped synthetic permission (not a grant to the real app), and stubbed scheduling/confirmation callbacks. They never send SMS, write the phone inbox, access real credentials, or contact OpenAI with a valid key. Simulation cannot verify Android reception, closed-app delivery, or receiver/delivery timings.

The tests enter these stages independently:

| Boundary | How it is exercised |
| --- | --- |
| SMS/chat JSON into Python | Windows unit tests call the production entry points with synthetic JSON. |
| Few-shot requests and boolean/category parsing | Unit tests replace HTTP responses with controlled successes, uncertainty, and failures. |
| Expenditure-to-category gate | Android tests call the production gate with stub callbacks; false/failed results and withdrawn consent must not invoke category inference. |
| Kotlin to packaged Python | Device tests call the real `PythonBridge` and interpreter bundled in the APK. |
| Dedicated API keys | Device tests mock both packaged inference helpers and forbid HTTP, checking that each receives only its matching key. Settings reject missing/equal/whitespace-containing keys; disable/re-enable invalidates old sessions without exposing keys in public state. |
| Chat and consent controls | Compose UI tests type input and click controls; consent tests use stub callbacks. |
| Classification persistence | Isolated history survives 26 messages and retains older Waiting work; stale results remain blocked after pause/clear. |
| Credential restart recovery | Isolated vaults use real Android Keystore and synthetic keys to recover remembered consent/session, forget keys, or fail closed after corruption; no real credentials are accessed. |
| Performance measurements | Separate benchmarks measure JSON serialization and local handoff stages. |
| Shared processor and simulator | Real local Python handoff for both sources; isolated persistence/provenance, stale/permission/pause/clear guards, cloud-off entry point, editable samples, input bounds, and cancelled/confirmed charge prompts. |
| Actual SMS delivery and hosted predictions | Manual checks and the explicitly invoked paid evaluation. |

## Python unit tests: 54 checks

All Python unit files are under `android-companion/python-tests/`. They import the same Python source that is packaged in the APK, under `android-companion/app/src/main/python/`.

| File | Tests | Coverage |
| --- | ---: | --- |
| `test_orchestrator.py` | 9 | SMS acknowledgement, required fields, malformed JSON, invalid types, nonnegative timestamps, Unicode code-point counts, and a receipt that does not echo the sender/body. |
| `test_chat.py` | 4 | Correlated capability replies, required fields, invalid types/blank input, generic errors, and no echo of private chat input. |
| `test_classifier.py` | 14 | Positive/negative few-shot examples, strict boolean schema, genuine booleans rather than truthy strings/numbers, malformed/refused/incomplete model output, input limits, missing credentials, mocked HTTPS settings, blocked redirects, safe HTTP/network errors, direct classifier routing, and rejection of malformed direct-call decisions/timings. |
| `test_usage.py` | 10 | Cached/cache-write accounting, no reasoning double-counting, long-context threshold, unknown prices/counts, safe telemetry, both real entry points, standard tier, reported costs on failed outputs, and timing without usage after network failure. |
| `test_category.py` | 17 | Both enums/valid pairs, all 24 sanitized user labels, personal purpose/uncertainty rules, and few-shot examples, uncertainty/untrusted-text instructions, model overrides, strict parser, safe errors/transport settings, missing keys, correlated results, and malformed category/timing rejection. |

Each `test_...` method counts as one test. Methods may use `subTest` to cover several inputs; those variants are not extra methods in the reported total.

The classifier tests exercise all 34 sanitized user-derived SMS examples with **mocked model labels**. Passing them establishes that the code handles the supplied labels correctly. It does not measure a real model's accuracy or prove that all future messages will be classified correctly.

Category tests similarly supply **mocked category/sub-category pairs**, not live predictions. A dedicated check preserves all 24 supplied labels with bank identifiers removed/replaced, and tests validate every disallowed pairing as a failure. These are preference/configuration and contract checks, not a classifier using rules or a semantic accuracy test. Passing them validates code contracts, not that Blinkit, BMTC, or a new merchant will be categorized correctly. Prompt rules require manual/model evaluation to measure semantic behavior.

### Run the full Python suite

From PowerShell, using the existing repository environment:

```powershell
cd C:\Users\unnia\expense-tracker-with-mcp
.\.venv\Scripts\Activate.ps1
cd .\android-companion
python -m unittest discover -s python-tests -p "test_*.py" -v
```

If no working environment exists, run the following from `android-companion` with Python 3.11 installed:

```powershell
py -3.11 -m venv .venv-tests
.\.venv-tests\Scripts\Activate.ps1
python -m unittest discover -s python-tests -p "test_*.py" -v
```

The unit suite requires only the Python standard library. No OpenAI key, phone connection, Android Studio, or `pip install` is needed. Use the environment's `Scripts\python.exe` directly if PowerShell blocks activation rather than changing system-wide execution policy.

To run just the classifier tests, from `android-companion`:

```powershell
python -m unittest discover -s python-tests -p "test_classifier.py" -v
```

Success ends with `Ran 54 tests` and `OK` for the full current suite. A failure includes the test name and failed assertion; `ERROR` usually means an exception or import/setup problem. Read the failure, fix the implementation or incorrect test expectation, and rerun that suite before rerunning everything.

To run just category tests:

```powershell
python -m unittest discover -s python-tests -p "test_category.py" -v
```

## Android integration and UI tests: 53 checks

Files are under `android-companion/app/src/androidTest/java/com/abijith/expensetracker/`. The tests run in a separate test APK on a development phone or emulator, using AndroidJUnitRunner and Compose UI testing.

| File | Tests | Coverage |
| --- | ---: | --- |
| `PythonBridgeInstrumentedTest.kt` | 4 | Real packaged SMS acknowledgement including Unicode, safe failure for invalid SMS, offline chat help, and safe failure for invalid chat. |
| `ChatScreenInstrumentedTest.kt` | 1 | Blank Send is disabled; enter/send a message, receive real Python help, switch to SMS diagnostics, and return with the conversation retained. |
| `SmsPerformanceInstrumentedTest.kt` | 3 | JSON serialization benchmarks for short/long/Unicode messages, warm bridge-stage benchmarks, and persisted timing-format round-tripping. |
| `ClassifierInstrumentedTest.kt` | 3 | Missing-key failure is never Other, packaged parser preserves true/false, and timing/status serialization recovers abandoned Running as Interrupted. |
| `ClassificationStoreInstrumentedTest.kt` | 4 | Prevent duplicate/stale attempts, check eligibility after pause/clear, persist category results, interrupt abandoned Running work, and retain 26 messages with older Waiting work eligible after reload. |
| `CloudControlsInstrumentedTest.kt` | 7 | Two-key/consent controls with stub callbacks, category/status/cost/timing display, opt-in remembering and forget controls, blocked enabling during restoration with visible fail-closed errors, both suggested labels, and old flat cards marked legacy without invented sub-categories. |
| `LlmUsageInstrumentedTest.kt` | 4 | Safe telemetry parsing, old/new result persistence, both stage timings, complete/unknown cost totals, and packaged Python telemetry with shared mocked transport aliases and HTTP forbidden. |
| `CloudKeysInstrumentedTest.kt` | 3 | Reject missing/equal/malformed keys or absent consent, keep both keys out of public state/results, invalidate prior sessions, and verify dedicated key routing through packaged Python with mocked helpers and HTTP prohibited. |
| `CloudPersistenceInstrumentedTest.kt` | 6 | Real Keystore encryption with isolated files/aliases: no plaintext keys, remembered consent/model/session recovery, memory-only mode, key rotation/forget, corrupt ciphertext and missing-key fail-closed recovery, and explicit consent. |
| `SmsHistoryInstrumentedTest.kt` | 1 | Stubbed SMS screen state with 26 cards: Show older messages exposes older captures without uploading or touching real history. |
| `CategoryInstrumentedTest.kt` | 6 | Python/Kotlin pair hierarchies match, both labels parse, invalid/missing pairs are rejected in the bridge, failed categories preserve Expenditure, routing remains gated, and legacy/current result formats persist. |
| `SmsProcessorInstrumentedTest.kt` | 7 | Shared processor and actual cloud-off simulator entry point; real packaged local Python, isolated saved source labels, no fabricated reception timings, rejected permissions/duplicates/stale tokens, pause/clear guards, safe handoff failure, input limits, and distinct IDs for identical tests. Scheduling is stubbed or forbidden, never real cloud work. |
| `SmsSimulationInstrumentedTest.kt` | 4 | Compose simulator UI: paused/blank/overlong/restoring submissions blocked, editable Unicode input, cloud-off callback, cloud charge confirmation/cancel, and synthetic sample buttons. All submit callbacks are stubbed. |

The storage tests override the store location with a unique synthetic cache directory. They clean only their own test files. The UI consent test does not enable real cloud uploads. Tests do not read the SMS inbox, send SMS, inject SMS broadcasts, or clear the user's saved capture history.

### Run from Android Studio

1. Open `android-companion` in Android Studio and let Gradle sync finish.
2. Connect a development Pixel or start a compatible emulator.
3. Open one of the thirteen test classes above.
4. Click the green run arrow beside the class and choose **Run**.
5. Inspect the test results and any failed assertion.

The tooling builds/installs the app and test APK and may restart the application. Memory-only mode needs key/consent re-entry; opted-in remembered settings restore automatically. Key tests now use isolated settings instances, ciphertext files, and unique Keystore aliases, so they never disable or remove your real remembered credentials. Updating with the same debug signing key does not require uninstalling or clearing storage.

### Run all thirteen classes from PowerShell

From `android-companion`, with Android Studio's JDK installed:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:connectedDebugAndroidTest
```

Gradle reports failures and writes connected-test reports under `app/build/reports/androidTests/connected/`. If running through ADB directly, inspect the test-runner result (`OK (53 tests)`), not just the shell exit code.

Generated `ExampleUnitTest` and `ExampleInstrumentedTest` were removed; they were template examples, not meaningful application coverage.

## Per-message cost verification

The ten Python usage tests use synthetic provider counts and published-rate arithmetic, not real bills. Android tests verify telemetry crossing the packaged Python bridge, display, persisted round-tripping, and totals without erasing timings or valid predictions. Missing telemetry is never labelled free. Existing three bottleneck/performance tests remain unchanged.

The current estimator supports only the exact default model and standard tier, with dated rates. See the [cost and timing design](../../android-companion/README.md#per-message-calculated-cost-and-bottleneck-timings) for cache-write accounting, unsupported pricing, failures, retry/history limitations and manual checks. A live model call and external billing reconciliation remain manual, explicitly chargeable steps; no Admin key belongs in the APK.

## Build and lint checks

From `android-companion`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

Outputs:

- Application APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Test APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- Lint report: `app/build/reports/lint-results-debug.html`.

These tasks build/analyze code; they do not run the device tests. `BUILD SUCCESSFUL` is not proof that SMS reception or model inference worked. Gradle, Android SDK, or dependencies may need internet on the build computer.

## Performance checks and bottleneck measurements

### Host Python benchmark

From `android-companion` with the Python environment active:

```powershell
python python-tests/benchmark_handoff.py
```

This invokes `orchestrator.handle_sms` five times to warm up, then measures 100 calls using a monotonic clock. It reports the median and nearest-rank 95th percentile. It excludes Android startup, Kotlin/Python bridge crossing, SMS delivery, and hosted classification. It has no pass/fail latency threshold.

### Device benchmarks

Run `SmsPerformanceInstrumentedTest.kt` and filter Android Studio Logcat by `SmsPerformanceTest`:

- JSON benchmark: 20 warm-ups, then 100 samples each for 160-character, 2,000-character, and 240-code-point Unicode messages. Assertions run outside the timed serialization interval.
- Warm bridge benchmark: one observed call, then 100 warm calls. Reports lock wait, serialization, runtime setup, Python call, receipt validation, and total bridge time.
- The first observed call is cold only if `runtime_started` is true; another test may already have started Python.
- Assertions require valid output and finite, nonnegative durations. They do not impose a speed target.

### Real-message timing boundaries

| Displayed measurement | Includes | Does not establish |
| --- | --- | --- |
| SMS timestamp to receiver entry | Wall-clock difference from the SMS-provided timestamp to receiver entry. | Pure carrier delivery or exact app-detection latency; sender/phone clocks can differ. |
| JSON encoding | Production Kotlin serializer only. | Python startup, inference, or SMS delivery time. |
| Receiver entry to JSON ready | Setup, executor wait, message preparation/save, bridge wait, and encoding. | Delivery before receiver entry or later model processing. |
| Complete local handoff | Shared-lock wait, encoding, runtime setup, Python receipt call, and validation. | Hosted classification latency. |
| Total analysis processing | Worker-side expenditure call and, if applicable, the category call and between-stage checks. | WorkManager scheduling/network-constraint wait, final persistence, or UI rendering. |
| Category processing | The category Kotlin/Python call, HTTP round trip, and enum parsing. | Expenditure stage, scheduling wait, persistence, or UI rendering. |
| Expenditure / category API round trip and validation | HTTP/network/model processing, response reading, and boolean/enum parsing. | Pure model compute time. |

Some values are totals containing other stages: do not add all displayed durations together. Use repeat measurements on the same device/build. Older saved messages have no retroactive receiver timings. For real captures, filter Logcat by `SmsPerformance`; receiver timing logs omit sender/body.

The MCP-only protocol suites and SDK compatibility script were removed with the unused integration. Functional validation remains covered by the direct classifier and chat tests.

## Optional paid model evaluation

Run only when you deliberately want to spend API credits. From `android-companion`, with `OPENAI_API_KEY` set privately in the process environment:

```powershell
python python-tests/evaluate_classifier.py
```

Never put a literal key in Git, screenshots, shared commands, or this guide. `OPENAI_CLASSIFIER_MODEL` can select a model override. If the key is missing, the script prints setup guidance, exits with code 1, and makes no requests.

The script calls `expenditure_classifier.classify_expenditure` **directly**, not the Android worker. It evaluates the expenditure decision only; it does not call or measure category identification. It sends sanitized examples excluded from the prompt verbatim, reports expected/actual labels, request failures, and API latency, and stops early on authentication/access/quota/request-configuration failures. Exit code 0 requires every evaluated case to succeed with the expected label.

Similar bank-message templates are a narrow evaluation. Results do not establish broad accuracy, transaction ownership, resistance to copied alerts, or end-to-end Android behavior. This script is excluded from `test_*.py` unittest discovery and is not run automatically.

## Manual demo checklist

Use ordinary synthetic SMS messages without personal details or verification codes. These steps may modify the local capture history; clearing history deletes saved captures, so only test that action if you intend to.

1. Open the app. Send a chat message and verify the local help response explains SMS capture, cloud consent, and which features are still planned.
2. Leave cloud off. Enable SMS capture and grant permission. Send an ordinary SMS from another phone; confirm one saved card and `Python: Acknowledged`.
3. Try Unicode and a multipart SMS. Check the full body and one logical card per message. Press Home, receive another SMS, and reopen the app; do not force-stop it for this check.
4. Pause capture, send a message, and confirm it is not added. Test denied/revoked permission and recovery using Android settings as needed.
5. Deliberately enable cloud with two distinct private keys and explicit upload consent. Send synthetic BMTC/Blinkit/food-delivery payment examples, a personal-name transfer, and an advertising example. Expect utilities/transport, utilities/groceries and fun/food, Not sure for both fields when the purpose is unknown, and category skipped for Other. Inspect separate timings or safe failures. An Expenditure result must remain Expenditure if category inference fails. These expectations need manual verification and are not guarantees.
6. Confirm that enabling cloud alone does not replay old history; use the per-card upload/reclassify button only if you intentionally want to upload that saved body.
7. For a closed-app/reboot check, optionally select Remember keys on this device before enabling. Follow the [background checklist](../../android-companion/README.md#manual-closed-app-check). Disable cloud and forget keys after the demo unless you deliberately want ongoing uploads. An already-sent API request cannot be recalled.

Android 17 may protect OTP SMS; RCS, WhatsApp, and email are not ordinary SMS and are outside the current capture implementation. Test ordinary SMS for reception checks.

## Coverage limits and troubleshooting

Not fully automated today: actual SMS delivery, permission dialogs, the complete receiver-to-WorkManager-to-hosted-model path, scheduling delay, real model accuracy, process/background behavior under all Android restrictions, or live classification through the current direct-call route. Recovery tests reconstruct isolated settings/storage instances rather than killing a real process. Real Keystore encryption is exercised, but actual SMS/worker-triggered cold process startup and reboot scheduling require manual verification.

Transaction-detail extraction, category confirmation/correction, financial database recording/querying, weekly reports, and training are not implemented, so these tests do not claim to verify them. An Expenditure label means the body describes a payment; a friend copying a bank alert is not distinguished from the user's own alert.

| Symptom | Check |
| --- | --- |
| Unexpected test counts | Current source defines 54 Python and 53 Android checks. The personal-category predecessor had 54/42, direct-classifier predecessor had 27/14, and retired MCP experiment had 43/16. Check the source-version note above. |
| Python not found / activation fails | Select a working Python 3.11 environment; use its executable directly if activation is blocked. |
| No connected device | Enable USB debugging, approve the connection, and select the Pixel/emulator in Android Studio. |
| Android build or Python bytecode compilation fails | Check the configured JDK, SDK components, Gradle sync, and build-time Python 3.11 matching the APK runtime. |
| Classification waits | Check internet, Android scheduling, capture permission, and the current cloud session. Unit tests do not measure queue wait. |
| Classification or category fails | Read the separate safe error; check key, API billing/quota/model access, or connectivity. Category failures preserve Expenditure and do not fabricate Other/Not sure. Manual reclassification repeats both stages for expenditures and may incur charges. |
| No captured SMS | Check permission/capture, reopen after force-stop, and make sure it was ordinary SMS rather than RCS. |

Before a demo, run the Python suite, build/lint, device suite, and then the manual checks. Keep live evaluation optional and intentional. See the [main project README](../../README.md) and [Android setup guide](../../android-companion/README.md) for architecture and setup.
