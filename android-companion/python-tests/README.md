# Tests — 0.12.0 category review

The full [testing guide](../../docs/wiki/Testing.md) lists test locations, coverage, Windows commands, timing measurements, and manual checks for both Python and Android.

Verified on October 4, 2026: **54 Python tests and 62 Android checks passed**. Debug app/test APK builds, unsigned release build, and Android lint passed. The debug app was installed on the Pixel 7a running Android 17 without clearing history or remembered keys. These are local runs, not GitHub Actions. Tag `v0.11.0` remains the older simulator checkpoint (54/53), not this review version.

## Run the offline Python suite

From the repository root, using the existing virtual environment:

```powershell
cd .\android-companion
..\.venv\Scripts\python.exe -m unittest discover -s python-tests -p "test_*.py" -v
```

Expected result: `Ran 54 tests` and `OK`. Tests use synthetic inputs and mocked API responses; no phone or OpenAI key is required. They do not establish live model accuracy.

## Run the Android checks

Connect a development phone or start an emulator. From `android-companion`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:connectedDebugAndroidTest
```

Expected result: **62 checks** across fifteen classes. This builds/installs development APKs and may restart the app. Tests use isolated synthetic history/settings and stubbed cloud scheduling or inference; they do not send SMS, clear real history, or make paid API calls.

New category-review tests under `app/src/androidTest/java/com/abijith/expensetracker/`:

- `CategoryReviewStoreInstrumentedTest.kt`: six checks for the uncertainty-only gate, custom-label persistence, original suggestion/timing preservation, paused capture, validation, stale/duplicate/cleared forms, separate message correlation, legacy loading and preserved feedback after reclassification.
- `CategoryReviewUiInstrumentedTest.kt`: three checks for empty fields/examples, required labels, correlated submission and saved display in Chat, plus retryable failures that preserve drafts. UI callbacks are stubbed; no cloud calls.

For a free manual review check, use an existing Expenditure with Not sure/Not sure. Open Chat, enter both labels, submit, and reopen the app to verify persistence. Do not clear real history as a test setup. Creating a new cloud prediction can cost money; saving review labels does not.

The new simulator regression tests are in `app/src/androidTest/java/com/abijith/expensetracker/`:

- `SmsProcessorInstrumentedTest.kt`: seven checks for shared processing, actual cloud-off submission, packaged Python acknowledgement, saved test labels, lifecycle guards, safe failures, and input limits.
- `SmsSimulationInstrumentedTest.kt`: four UI checks for editable samples, validation, cloud-off submission, and charge confirmation/cancellation.

## Manual simulator check

Use the [Simulate SMS instructions](../README.md#simulate-sms-without-another-phone). Cloud off tests only local processing; cloud on requires confirmation and can incur API charges. Test cards are labelled and retain normal costs/timings, but simulation does **not** verify SMS reception, receiver/delivery latency, or closed-app delivery. Keep occasional real SMS checks for those boundaries. Release builds do not include the simulator implementation.
