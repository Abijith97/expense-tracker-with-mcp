# Emulator SMS system tests

## Verified CI result

[Run 38162970973](https://github.com/Abijith97/expense-tracker-with-mcp/actions/runs/38162970973) passed for source `bb24dba`: **65 Python tests on each of Windows/Linux and 87 Android tests on API 35**. All seven system cases ran with zero failures, errors or skips. `sms-injection.json` confirms every expected scenario was injected, with no harness errors. Android JaCoCo reports **91.55% line / 72.51% branch** coverage; host Python remains **99% combined statement/branch**. Builds and lint passed; the normal debug APK artifact was delivered. The run finished October 11 UTC (October 12 IST).

These executable tests join the previously separate components: emulated SMS delivery → `SmsReceiver` → `SmsProcessor` → persisted capture → packaged Python acknowledgement → real WorkManager job → real Python prompts/transport/parsers → stored classification → real `MainActivity` UI.

Only the external HTTP boundary is replaced. The Python fake validates the actual request URL, dedicated synthetic key, strict schema, few-shot inputs and timeout, then supplies a response or error. It does not stub the receiver, worker, bridge, orchestrator or UI callbacks. No real API keys, paid requests or private SMS are used. This verifies response handling, not model prediction accuracy.

## Executable cases

| Test method in `SmsSystemInstrumentedTest` | Expected behaviour |
| --- | --- |
| `bmtcPaymentCanBeReviewedRecordedAndReloaded` | Synthetic Rs.23 BMTC SMS is captured, acknowledged, classified and categorized utilities/transport. Real UI confirms the pair and opts into recording. A fresh disk reader and Activity recreation preserve the choices; review makes no extra inference call. |
| `nonExpenditureNeverCallsCategoryOrOffersRecording` | Synthetic offer is Other; category HTTP, validation and recording controls are absent. |
| `exhaustedTimeoutIsFailureNotOtherAndSmsIsRetained` | Three classification attempts exhaust the bounded retry policy. The card shows a safe timeout error, not Other; the SMS remains on disk. |
| `transientTimeoutRecoversWithoutRepeatingCategory` | A timeout then success uses two classification attempts and only one category call. |
| `malformedBooleanIsRejectedWithoutCategoryCall` | A string `"true"` is rejected as invalid output, with no category call. |
| `categoryAuthenticationFailurePreservesExpenditure` | Category HTTP 401 fails once without retry, while the successful expenditure decision remains intact and the UI shows the key error. |
| `uncertainCategoryAcceptsCustomLabelsWithoutAnotherCall` | Not sure opens the actual form; submitted custom labels persist separately from the model output without another inference call. |

The recording switch saves an inclusion choice only. These tests do not claim a finance-ledger write or model training. Reload checks use a fresh `SmsStore` and Activity recreation, not force-stopped process recovery. Background delivery, reboot, carrier behaviour and vendor battery policies still need separate checks.

## Files and CI

- [Instrumented system tests](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/SmsSystemInstrumentedTest.kt): JUnit 4 / Compose tests using the real Activity.
- [HTTP fake](../../android-companion/app/src/androidTest/assets/system_test_transport.py): asset in the **test APK only**, absent from debug/release application APKs. Patches `urllib.request.build_opener` below the production transport and restores it after every test.
- [Host emulator driver](../../android-companion/ci/run_android_system_tests.py): watches readiness markers containing a unique run token, then invokes `adb emu sms send` with a fixed synthetic message. Never injects until the HTTP fake is ready.
- [Harness unit tests](../../android-companion/python-tests/test_system_test_harness.py): nine offline checks for scenarios, real Python response/error contracts, patch restoration, stale markers, fresh-emulator reset and physical-device/multiple-device rejection.
- [Workflow](../../.github/workflows/ci.yml): runs the full Android suite through the host driver with JaCoCo; uploads `sms-injection.json` alongside coverage, lint and JUnit results. Missing scenarios, injection errors or test failures fail CI and block successful APK delivery.

Expected totals are **65 Python tests** and **87 Android tests** (previous 56/80 plus 9/7). A normal instrumented run without the driver skips the seven new system tests; the existing 80 still run. Test totals are not a claim that a CI run has passed—check the matching Actions run.

## Run locally

Use a **disposable emulator**, not your phone. Disconnect other devices. The driver validates an `emulator-*` serial and `ro.kernel.qemu=1`, rejects multiple connected devices, and requires explicit acknowledgement before clearing this application's emulator data. The instrumented gate runs before launching `MainActivity`, so ordinary phone test runs skip the suite without opening the real Activity or modifying history.

From `android-companion`, with Java/Python/adb available and one booted emulator:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:Path = 'C:\Users\unnia\AppData\Local\Android\Sdk\platform-tools;' + $env:Path
..\.venv\Scripts\python.exe ci\run_android_system_tests.py --serial emulator-5554 --allow-disposable-emulator-reset --coverage
```

This runs **all** instrumented tests, not only the seven system cases. The emulator SMS inbox also receives synthetic messages; no SMS is sent to a real phone or network. The disposable emulator can be discarded afterward. The driver clears only this POC's app data on that emulator, never a physical phone or its inbox.

Coverage reports remain Kotlin/Java JaCoCo and host-Python coverage.py; embedded Python execution in the emulator is not included in the JaCoCo denominator. Mocked request times are not real LLM latency measurements.
