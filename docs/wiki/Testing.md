# Testing the Android AI Expense Tracker

This guide explains the POC's test suites, their code locations, how to run them on Windows, and what the results mean. Tests are developer-run checks; they do not run automatically when an SMS arrives.

This guide is versioned under `docs/wiki/`, with a copy in the [GitHub Wiki](https://github.com/Abijith97/expense-tracker-with-mcp/wiki/Testing). Keeping the source here versions it alongside the code; repository privacy is unchanged.

For the payment-to-card walkthrough, see [Persona-based testing: BMTC payment of Rs.23](Persona-BMTC-Expenditure.md). The [testing wiki index](Home.md) links separate component pages with method purposes, existing test evidence and proposed missing cases. These are documented test contracts, not newly implemented executable tests. The separate Wiki Git repository became accessible on October 11 after the initial Home page was created.

## Scope and current verification

Last verified: **October 10, 2026**. The current build is `0.13.0-expenditure-review`. Python tests, debug/release APK builds, and lint passed on Windows. All seventeen Android test classes passed on the Pixel 7a running Android 17 through AndroidJUnitRunner after updating debug app/test APKs without clearing storage. Review controls exist in both variants; the release simulator remains an empty UI/rejecting stub. Isolated Compose tests use an app-themed `ComposeTestActivity` in the debug source set; it is absent from release builds.

| Check | Last verified result | What it establishes |
| --- | --- | --- |
| Python unit suite | 56 tests passed | Python input/output contracts, strict boolean/category parsing, repayment prompt examples, safe telemetry, transport timeout/deferral and mocked inference. |
| Android integration/UI suite | 80 tests passed | Packaged Python, routing, consent/cost UI, encrypted recovery, history, simulator/shared processing, timings, bounded retries, recording intent and category validation/editing work on the phone. Does not verify live SMS delivery, predictions, or learning accuracy. |
| Debug application/test and unsigned release APK builds | Passed | Both source-set variants build with embedded Python; the simulator implementation is debug-only. |
| Android lint | Passed | No lint errors and no remaining unused-resource warnings. Other warnings remain; this is not a claim of zero warnings. |
| Live category suggestions | Needs a manual synthetic SMS check | Automated checks do not contact OpenAI with a valid key or establish category accuracy. |

**Source version:** `0.13.0-expenditure-review` has 56 Python and 80 Android checks. The preceding `0.12.0-category-review` build has 54/62. Tag `v0.11.0` has the simulator build (54/53). Commit `9e52fb4` has the prior personal-category/background build (54/42); `79704f8` has the two-key/category build (41/24); `c55c370` has direct classification (27/14). The retired MCP experiment `22b14ee` had 43/16. Compare totals only against matching source versions.

GitHub Actions runs the suites through [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) on pushes to `main`, pull requests targeting `main`, and manual runs. Android Studio/Gradle and Python unittest remain the test runners; Actions automates their execution. See [CI and coverage](#continuous-integration-and-coverage).

iOS development and proposed per-feature acceptance tests are tracked in the [iOS guide](../../ios-companion/README.md). No iOS project or tests are implemented or verified yet; the Android/Python counts above are not iOS results.

The unchanged `v0.11.0` tag identifies the previous SMS-simulator milestone (version code 13). The current review build has version code 15. A short [test README](../../android-companion/python-tests/README.md) provides quick-start commands; this guide is the detailed coverage reference. A Git tag is a source checkpoint, not APK distribution or a GitHub Release.

## Continuous integration and coverage

The full CI emulator suite now also includes [seven SMS system tests](System-Tests-Emulator.md), plus nine offline harness checks. These cover the real receiver/worker/Python/UI journey with only HTTP replaced. Historical verified totals below remain the prior baseline; use the matching Actions run for the new suite's result.

The workflow has two stages:

1. **Python:** run the existing offline unittest suite with Python 3.11 on both Windows and Linux. `coverage.py` measures the eight production Python modules, including branches. Generate HTML/XML/JSON reports and fail the job below 85% combined coverage.
2. **Android:** after both Python jobs pass, build debug/test/unsigned-release APKs and run lint on Linux. Start a fresh Android 15 (API 35) x86_64 emulator and run all instrumented JUnit/Compose tests through the SMS injection driver with JaCoCo coverage. The app still compiles/targets API 37; the emulator is one supported runtime, not a replacement for the Pixel's Android 17 checks. Rebuild the debug APK without instrumentation and deliver it as an artifact after all checks pass.

No OpenAI keys or real messages are supplied to CI. The optional paid `evaluate_classifier.py` script is excluded. The GitHub runner provides its own emulator; your phone/computer need not be connected or running. Actions usage/artifact storage counts toward the repository owner's GitHub allowance.

| Part | Runner/framework | Coverage tool | What coverage includes |
| --- | --- | --- | --- |
| Python logic | Standard-library `unittest` | `coverage.py` 7.14.3 | Production Python statements and branches exercised by the host tests. |
| Android integration/UI | JUnit 4, AndroidJUnitRunner, Compose UI testing, Gradle | JaCoCo 0.8.14 through AGP | Kotlin/Java app code exercised by instrumented tests. Includes app/debug source; does not instrument embedded Python or native libraries. |
| Automation/delivery | GitHub Actions | Uploaded reports | Runs tests/builds and provides a debug APK after successful checks. |

GoogleTest is a C++ test framework and does not fit these Kotlin/Python suites. Existing tests need no framework migration. These tools also run locally from Android Studio or PowerShell. References: [Android coverage tasks](https://developer.android.com/studio/test/coverage-report), [Coverage.py](https://coverage.readthedocs.io/en/7.14.3/), [emulator runner](https://github.com/ReactiveCircus/android-emulator-runner), and [GoogleTest](https://github.com/google/googletest).

**Initial local Python baseline:** 56 tests passed; 99% combined statement/branch coverage (268 statements, 78 branches; two missed statements and two partial branches). This measures exercised code, not prediction accuracy. Kotlin has its own report and denominator; do not average the two percentages. Android coverage is reported without a minimum-percentage gate. Tests/lint/report generation must still pass. Emulator timings and instrumented coverage runs are not device performance benchmarks.

### Find results and download the APK

Open the repository's **Actions** tab, select **Tests, coverage and demo APK**, then open a run. Job logs contain failed assertions and Python coverage totals. Its **Artifacts** list contains:

- `python-coverage-ubuntu-24.04` and `python-coverage-windows-latest`: open `html/index.html`; XML/JSON support machine processing.
- `android-test-coverage-and-lint`: Android test results, lint and JaCoCo coverage. Open the coverage `index.html` under `androidTest/debug/connected/`.
- `expense-tracker-demo-apk`: `app-debug.apk`, available after a successful push/manual run; no APK delivery from pull-request runs.

Artifacts are retained for 14 days. Reports are uploaded on failure when available; a failed test/build cannot produce a successful demo artifact. Coverage reports are normally generated only after their relevant tests pass. Download and extract the artifact before opening HTML. For a manual rerun, use **Run workflow** in Actions.

Delivery currently means a downloadable debug APK. There is no Play Store deployment or automatic phone installation. A GitHub runner generates its own debug signing key; its APK cannot update an existing locally signed install. Continue local builds for updating a phone while preserving its data. Store signing/distribution credentials are not needed for this workflow.

### Run Python coverage locally on Windows

From the repository root:

```powershell
cd .\android-companion
..\.venv\Scripts\python.exe -m pip install -r python-tests\requirements-dev.txt
..\.venv\Scripts\python.exe -m coverage run -m unittest discover -s python-tests -p "test_*.py" -v
..\.venv\Scripts\python.exe -m coverage html --fail-under=0
..\.venv\Scripts\python.exe -m coverage xml --fail-under=0
..\.venv\Scripts\python.exe -m coverage json --fail-under=0
..\.venv\Scripts\python.exe -m coverage report
```

Configuration is in `android-companion/.coveragerc`. Reports go under `build/python-coverage/`, which is ignored by Git. `requirements-dev.txt` installs test tooling only, not dependencies bundled in the APK. Ordinary unittest runs still require only the standard library.

### Run Android coverage locally

Use a development emulator or phone. From `android-companion`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat -Pcoverage=true :app:createDebugAndroidTestCoverageReport --console=plain
```

That task runs `connectedDebugAndroidTest` and generates the report under `app/build/reports/coverage/androidTest/debug/connected/`. `-Pcoverage=true` enables instrumentation only for the debug build. To restore a normal demo APK and its timings, build again without that flag:

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

Normal builds leave coverage disabled; release builds are unaffected. All CI actions are pinned to explicit revisions, and the workflow has read-only repository permissions. New pushes cancel older runs for the same branch. These checks do not configure branch protection or guarantee that a failing PR cannot be merged.

## What gets tested in the normal flow

For an SMS, Android captures and saves the message, calls embedded Python for a short acknowledgement, and optionally schedules a separate cloud-classification worker. That worker calls `orchestrator.classify_sms` for the boolean check. The bridge supplies a dedicated classification key. Only an Expenditure result with current permission/capture/consent enters `orchestrator.identify_sms_category` for the second hosted call, using its own category key. Kotlin validates each result and updates the same captured-message card. A category failure must preserve the expenditure label. Each stage uses a 60-second socket-operation timeout and at most two retries for transient network, timeout, or server errors. Category retries do not repeat successful classification. Retry tests inject errors, clocks and sleeps; they do not simulate a real carrier outage.

For chat, Kotlin calls the Python orchestrator, which calls `app_help.get_help()` directly. Every valid chat message currently receives capability information; spending queries are not implemented yet.

Separately, SMS cards and `ChatScreen` derive validation controls from retained Expenditure cards: predicted pairs get Yes/No, while Not sure/Not sure or No opens empty fields with examples and custom labels. Submit calls `SmsStore.confirmCategory` locally with the exact SMS and attempt IDs. Saved labels can be edited through `editCategory`, which also checks the observed review timestamp to reject stale editors. A recording switch persists a separate optional inclusion choice, defaulting off. These controls do not call Python/OpenAI, train weights, or insert a ledger expense. Pending forms and submitted choices survive restarts; saved labels remain separate from model suggestions. Pause is allowed, stale/duplicate/cleared attempts are rejected, and clear removes annotations with their SMS copies. Unsaved drafts are not retained.

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
| Category validation, editing and recording intent | Eleven isolated-store checks and six form UI checks cover custom labels, Yes/No, edits/cancellation, persistence, stale editors and separate human/model data. Three switch UI checks cover opt-in/out and failed saving. |
| Network retries | Seven Android checks cover bounded attempts, backoff, cancellation, permanent errors, stage independence, attempt persistence and unknown total costs after retry. Python checks cover timeout configuration, repayment prompt examples, and deferred retry when a server requests a delay. |
| Real receiver-to-UI SMS path | Seven emulator system tests inject synthetic telephony SMS and fake HTTP responses/errors; see the [system-test guide](System-Tests-Emulator.md). |
| Real carrier/device delivery and hosted predictions | Manual checks and the explicitly invoked paid evaluation. |

## Python unit tests: 56 checks

All Python unit files are under `android-companion/python-tests/`. They import the same Python source that is packaged in the APK, under `android-companion/app/src/main/python/`.

| File | Tests | Coverage |
| --- | ---: | --- |
| `test_orchestrator.py` | 9 | SMS acknowledgement, required fields, malformed JSON, invalid types, nonnegative timestamps, Unicode code-point counts, and a receipt that does not echo the sender/body. |
| `test_chat.py` | 4 | Correlated capability replies, required fields, invalid types/blank input, generic errors, and no echo of private chat input. |
| `test_classifier.py` | 16 | Positive/negative few-shot examples, credit-card repayment exclusions, strict boolean schema, genuine booleans rather than truthy strings/numbers, malformed/refused/incomplete model output, input limits, missing credentials, 60-second mocked HTTPS settings, blocked redirects, safe HTTP/network errors, Retry-After deferral, direct routing, and rejection of malformed decisions/timings. |
| `test_usage.py` | 10 | Cached/cache-write accounting, no reasoning double-counting, long-context threshold, unknown prices/counts, safe telemetry, both real entry points, standard tier, reported costs on failed outputs, and timing without usage after network failure. |
| `test_category.py` | 17 | Both enums/valid pairs, all 24 sanitized user labels, personal purpose/uncertainty rules, and few-shot examples, uncertainty/untrusted-text instructions, model overrides, strict parser, safe errors/transport settings, missing keys, correlated results, and malformed category/timing rejection. |

Each `test_...` method counts as one test. Methods may use `subTest` to cover several inputs; those variants are not extra methods in the reported total.

The classifier tests exercise all 34 original sanitized user-derived SMS examples with **mocked model labels**, plus three sanitized credit-card repayment negatives. The prompt excludes bill repayments while keeping actual card purchases as expenditures. Passing these checks establishes prompt contents and response handling; it does not measure a real model's accuracy or prove that future messages will be classified correctly.

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

Success ends with `Ran 56 tests` and `OK` for the full current suite. A failure includes the test name and failed assertion; `ERROR` usually means an exception or import/setup problem. Read the failure, fix the implementation or incorrect test expectation, and rerun that suite before rerunning everything.

To run just category tests:

```powershell
python -m unittest discover -s python-tests -p "test_category.py" -v
```

## Android integration and UI tests: 80 checks

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
| `CategoryReviewStoreInstrumentedTest.kt` | 11 | Uncertain/predicted-pair validation, custom labels/local saving while paused, restart and old-card compatibility, immutable suggestion/timings, invalid labels, duplicate/stale/cleared forms, separate IDs, recording choices, version-checked editing, and reclassification resetting inclusion while retaining human review. Isolated files only. |
| `CategoryReviewUiInstrumentedTest.kt` | 6 | Empty fields/examples, required labels, correlated submission, saved Chat display, failed save/retry, predicted Yes/No, and prefilled editing with cancellation/save. Stub callbacks only. |
| `RecordingChoiceInstrumentedTest.kt` | 3 | Switch defaults off, persists selection/exclusion through stub callbacks, handles failed saving, and is absent for Other/failed classifications. |
| `InferenceRetryInstrumentedTest.kt` | 7 | Success without retry, transient recovery and three-request cap, permanent/deferred errors, consent withdrawal, time window/disabled retries, independent category retry, and persisted attempt counts with unknown total cost. Injected clocks/sleeps and mocked calls. |

The storage tests override the store location with a unique synthetic cache directory. They clean only their own test files. The UI consent test does not enable real cloud uploads. Tests do not read the SMS inbox, send SMS, inject SMS broadcasts, or clear the user's saved capture history.

### Run from Android Studio

1. Open `android-companion` in Android Studio and let Gradle sync finish.
2. Connect a development Pixel or start a compatible emulator.
3. Open one of the seventeen test classes above.
4. Click the green run arrow beside the class and choose **Run**.
5. Inspect the test results and any failed assertion.

The tooling builds/installs the app and test APK and may restart the application. Memory-only mode needs key/consent re-entry; opted-in remembered settings restore automatically. Key tests now use isolated settings instances, ciphertext files, and unique Keystore aliases, so they never disable or remove your real remembered credentials. Updating with the same debug signing key does not require uninstalling or clearing storage.

### Run all seventeen classes from PowerShell

From `android-companion`, with Android Studio's JDK installed:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:connectedDebugAndroidTest
```

Gradle writes connected-test reports under `app/build/reports/androidTests/connected/`. Through ADB, inspect the runner result (`OK (80 tests)`), not just the shell exit code. The final October 10 device run reported 80 checks in 36.363 seconds; this suite duration is not SMS-processing latency. An earlier run failed at the generic Compose test-host setup with “No compose hierarchies found.” Switching isolated UI tests to the explicit debug-only activity resolved those failures without changing their functional assertions.

Generated `ExampleUnitTest` and `ExampleInstrumentedTest` were removed; they were template examples, not meaningful application coverage.

## Per-message cost verification

The ten Python usage tests use synthetic provider counts and published-rate arithmetic, not real bills. Android tests verify telemetry crossing the packaged Python bridge, display, persisted round-tripping, and totals without erasing timings or valid predictions. Missing telemetry is never labelled free. Existing three bottleneck/performance tests remain unchanged.

The current estimator supports only the exact default model and standard tier, with dated rates. See the [cost and timing design](../../android-companion/README.md#per-message-calculated-cost-and-bottleneck-timings) for cache-write accounting, unsupported pricing, failures, retry/history limitations and manual checks. A live model call and external billing reconciliation remain manual, explicitly chargeable steps; no Admin key belongs in the APK.

Automatic retries retain per-stage request counts. Usage describes only the final response, so the combined analysis estimate is unavailable after any retry; timed-out requests may still be charged. The retry tests check that partial usage is not presented as a complete bill.

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
| Total analysis processing | All worker-side expenditure/category attempts, retry backoff, and between-stage checks. | WorkManager scheduling/network-constraint wait, final persistence, or UI rendering. |
| Category processing | All category attempts, backoff, bridge/HTTP round trips, and enum parsing. | Expenditure stage, scheduling wait, persistence, or UI rendering. |
| Expenditure / category API round trip and validation | Final request's HTTP/network/model processing, response reading, and parsing. | Pure model compute time or duration of earlier attempts. |

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
8. On an existing Expenditure card, select and exclude recording, then reopen the app to check persistence. Confirm a predicted category with Yes, or use No/Not sure to enter custom labels. Edit the saved labels, check Cancel edit and Save changes, and verify the original suggestion remains visible. These actions are local and require no API credits.
9. For an intentional paid prompt check, use a synthetic credit-card bill-payment confirmation and an actual card-purchase example. Expect Other for the repayment and Expenditure for the purchase. Existing saved predictions are not automatically rerun when the prompt changes.

Android 17 may protect OTP SMS; RCS, WhatsApp, and email are not ordinary SMS and are outside the current capture implementation. Test ordinary SMS for reception checks.

## Coverage limits and troubleshooting

Not fully automated today: actual SMS delivery, permission dialogs, the complete receiver-to-WorkManager-to-hosted-model path, scheduling delay, real model accuracy, process/background behavior under all Android restrictions, or live classification through the current direct-call route. Recovery tests reconstruct isolated settings/storage instances rather than killing a real process. Real Keystore encryption is exercised, but actual SMS/worker-triggered cold process startup and reboot scheduling require manual verification.

Category confirmation/correction/editing and recording intent are implemented. Automatic feedback reuse, extraction, expense-ledger operations, spending queries, weekly reports, and training remain unimplemented. An Expenditure label describes payment text, not verified ownership; a friend copying a bank alert is not distinguished from the user's own alert.

| Symptom | Check |
| --- | --- |
| Unexpected test counts | Current source defines 56 Python and 80 Android checks; tag v0.11.0 has 54/53. Check the source-version note before comparing older milestones. |
| Python not found / activation fails | Select a working Python 3.11 environment; use its executable directly if activation is blocked. |
| No connected device | Enable USB debugging, approve the connection, and select the Pixel/emulator in Android Studio. |
| Android build or Python bytecode compilation fails | Check the configured JDK, SDK components, Gradle sync, and build-time Python 3.11 matching the APK runtime. |
| Classification waits | Check internet, Android scheduling, capture permission, and the current cloud session. Unit tests do not measure queue wait. |
| Classification or category fails | Read the separate safe error; check key, API billing/quota/model access, or connectivity. Category failures preserve Expenditure and do not fabricate Other/Not sure. Manual reclassification repeats both stages for expenditures and may incur charges. |
| Compose test reports no hierarchies | Rebuild and install both matching debug app/test APKs. Isolated UI tests require the debug-only `ComposeTestActivity`; the chat integration test uses `MainActivity`. Do not clear real app data to repair a test-host problem. |
| No captured SMS | Check permission/capture, reopen after force-stop, and make sure it was ordinary SMS rather than RCS. |

Before a demo, run the Python suite, build/lint, device suite, and then the manual checks. Keep live evaluation optional and intentional. See the [main project README](../../README.md) and [Android setup guide](../../android-companion/README.md) for architecture and setup.
