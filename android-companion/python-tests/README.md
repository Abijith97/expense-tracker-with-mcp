# Tests — v0.11.0 SMS simulator

The full [testing guide](../../docs/wiki/Testing.md) lists test locations, coverage, Windows commands, timing measurements, and manual checks for both Python and Android.

Verified on October 4, 2026: **54 Python tests and 53 Android checks passed**. Debug app/test APK builds, the unsigned release build, and Android lint passed. The debug app was installed on the Pixel 7a running Android 17 without clearing history or remembered keys. These results are from local runs, not GitHub Actions.

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

Expected result: **53 checks** across thirteen classes. This builds/installs development APKs and may restart the app. Tests use isolated synthetic history/settings and stubbed cloud scheduling or inference; they do not send SMS, clear real history, or make paid API calls.

The new simulator regression tests are in `app/src/androidTest/java/com/abijith/expensetracker/`:

- `SmsProcessorInstrumentedTest.kt`: seven checks for shared processing, actual cloud-off submission, packaged Python acknowledgement, saved test labels, lifecycle guards, safe failures, and input limits.
- `SmsSimulationInstrumentedTest.kt`: four UI checks for editable samples, validation, cloud-off submission, and charge confirmation/cancellation.

## Manual simulator check

Use the [Simulate SMS instructions](../README.md#simulate-sms-without-another-phone). Cloud off tests only local processing; cloud on requires confirmation and can incur API charges. Test cards are labelled and retain normal costs/timings, but simulation does **not** verify SMS reception, receiver/delivery latency, or closed-app delivery. Keep occasional real SMS checks for those boundaries. Release builds do not include the simulator implementation.
