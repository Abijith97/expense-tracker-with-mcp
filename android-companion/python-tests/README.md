# Tests — 0.13.0 expenditure review

The full [testing guide](../../docs/wiki/Testing.md) lists test locations, coverage, Windows commands, timing measurements, and manual checks for both Python and Android.

Verified on October 10, 2026: **56 Python tests and 80 Android checks passed**. Debug app/test APK builds, unsigned release build, and Android lint passed. The debug app was installed on the Pixel 7a running Android 17 without clearing history or remembered keys. These are local runs, not GitHub Actions. Tag `v0.11.0` remains the older simulator checkpoint (54/53), not this review version.

## Run the offline Python suite

GitHub Actions also runs these tests on Windows/Linux and the Android checks in an emulator on pushes/PRs/manual runs. Python coverage has an 85% minimum; Android JaCoCo coverage is reported separately. The initial local Python result is 99% combined statement/branch coverage. See [CI, artifacts and local coverage commands](../../docs/wiki/Testing.md#continuous-integration-and-coverage).

From the repository root, using the existing virtual environment:

```powershell
cd .\android-companion
..\.venv\Scripts\python.exe -m unittest discover -s python-tests -p "test_*.py" -v
```

Expected result: `Ran 56 tests` and `OK`. Tests use synthetic inputs and mocked API responses; no phone or OpenAI key is required. New checks cover sanitized credit-card repayment negatives, the 60-second timeout and server-requested retry deferral. They do not establish live model accuracy.

## Run the Android checks

Connect a development phone or start an emulator. From `android-companion`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:connectedDebugAndroidTest
```

Expected result: **80 checks** across seventeen classes. This builds/installs development APKs and may restart the app. Tests use isolated synthetic history/settings and stubbed cloud scheduling or inference; they do not send SMS, clear real history, or make paid API calls. Isolated UI tests use the debug-only `ComposeTestActivity`; the real chat screen test uses `MainActivity`.

New category-review tests under `app/src/androidTest/java/com/abijith/expensetracker/`:

- `CategoryReviewStoreInstrumentedTest.kt`: eleven checks for predicted/uncertain pairs, custom-label persistence, original suggestion/timing preservation, paused capture, stale/duplicate/cleared forms, separate message correlation, legacy loading, recording choices and timestamp-checked editing.
- `CategoryReviewUiInstrumentedTest.kt`: six checks for fields/examples, required labels, correlated submission and saved display, safe failure/retry, Yes/No, and edits/cancellation. UI callbacks are stubbed.
- `RecordingChoiceInstrumentedTest.kt`: three checks for the initially off switch, select/exclude, failed saving and ineligible messages.
- `InferenceRetryInstrumentedTest.kt`: seven checks for attempt limits/backoff, permanent errors, cancellation/time window, independent category retries, persisted counts and unknown total costs. Clocks, sleeps and API calls are injected.

For a free manual review check, use an existing Expenditure. Toggle recording, confirm the predicted pair with Yes, or use No/Not sure to enter both labels. Edit a saved category, test Cancel edit and Save changes, and reopen the app to verify persistence. Controls appear in Chat and SMS cards. Recording stores intent for the future ledger function; it does not insert an expense yet. Creating a new cloud prediction can cost money; saving choices/labels does not.

The new simulator regression tests are in `app/src/androidTest/java/com/abijith/expensetracker/`:

- `SmsProcessorInstrumentedTest.kt`: seven checks for shared processing, actual cloud-off submission, packaged Python acknowledgement, saved test labels, lifecycle guards, safe failures, and input limits.
- `SmsSimulationInstrumentedTest.kt`: four UI checks for editable samples, validation, cloud-off submission, and charge confirmation/cancellation.

## Manual simulator check

Use the [Simulate SMS instructions](../README.md#simulate-sms-without-another-phone). Cloud off tests only local processing; cloud on requires confirmation and can incur API charges. Test cards are labelled and retain normal costs/timings, but simulation does **not** verify SMS reception, receiver/delivery latency, or closed-app delivery. Keep occasional real SMS checks for those boundaries. Release builds do not include the simulator implementation.
