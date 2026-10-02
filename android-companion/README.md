# Android app — milestone 3: local chat and SMS

The Android application now provides its own Chat tab alongside SMS capture diagnostics. Both paths call Python running inside the APK. App version: `0.3.0-app-chat` (`versionCode` 3).

Chat currently returns built-in help for every message, including what the app can do and which finance features are planned. It does not yet perform LLM inference, call MCP tools, record expenses, or answer spending questions. The app runs without a WhatsApp account, Twilio, ngrok, or a separately hosted backend.

Capture starts **off** on a fresh installation. When you enable it and grant Android's SMS permission, the app keeps the latest 20 newly received SMS messages in app-private local storage. Each entry contains the sender, body, SMS timestamp, capture timestamp, and handoff status. All ordinary SMS messages are captured while enabled; financial classification comes later.

The Python entry points currently validate SMS/chat input and return a receipt or built-in help. They use only Python's standard library: no extra Python packages, LLM, MCP, network connection, or finance-database update. The phone needs no separate Python installation.

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
3. `PythonBridge` starts the embedded Python runtime if needed and calls `orchestrator.handle_sms(...)` with a JSON payload.
4. Python validates the fields and returns a JSON receipt.
5. Kotlin checks the receipt and updates the **same saved message** to `Python: Acknowledged`.

The displayed receipt contains only `schema_version`, `received`, `message_id`, `character_count`, `python_version`, and `processed_at_utc`. `received: true` means Python accepted the handoff; it does **not** mean the SMS is a financial transaction. Sender and message body are not echoed in this receipt.

`Handoff time` measures the bridge call, including Python startup on the first call in an app process. It excludes time spent waiting for SMS delivery and the executor. Later calls can be quicker because Python is already started. Status/timing diagnostics may appear in Logcat; the Python function does not print, and message content is not logged.

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

The app requests `RECEIVE_SMS` for Android's new-message broadcast. It does not become the default messaging app, read your existing inbox, send SMS, or upload messages. This milestone does not request `READ_SMS`, `SEND_SMS`, or `INTERNET` permission. Message bodies are not written to app logs, and the stored history is excluded from Android backup/transfer rules.

## Automated checks

There are 13 Python unit tests on Windows, four Android bridge tests, and one Android chat screen test. All input messages are synthetic. The tests do not read your inbox, send SMS, or clear the app's saved capture history.

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

The test runner imports the same `app/src/main/python/orchestrator.py` that goes into the APK. Nine SMS tests verify valid receipts, required fields, invalid input, Unicode character counts, UTC timestamps, and omission of SMS content from receipts/errors. Four chat tests verify correlated capability replies, required fields, invalid input, and safe errors. Each `test_...` method runs independently. `subTest` checks several variants, such as removing each required field. A failure displays the test name and the expected versus actual result. Host tests do not prove that Python was packaged correctly; the Android bridge tests check that boundary.

For an optional performance measurement:

```powershell
python python-tests/benchmark_handoff.py
```

This reports median and 95th-percentile processing time for 100 calls after warm-up. It measures host Python only, excluding interpreter startup, Android, the bridge, and SMS delivery. Compare runs on the same machine; timings have no pass/fail threshold.

### Android integration tests

The UI tests use Espresso `3.7.0`, AndroidX JUnit `1.3.0`, and the test runner `1.7.0`. The template's Espresso `3.5.1` failed on Android 17 while reflecting on `InputManager.getInstance`; [Espresso 3.7.0's release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0) document the change to use `getSystemService` instead.

1. Open `app/src/androidTest/java/com/abijith/expensetracker/PythonBridgeInstrumentedTest.kt` or `ChatScreenInstrumentedTest.kt` in Android Studio.
2. Connect your Pixel or start a supported emulator.
3. Click the green run arrow beside the test class and choose **Run**. Android Studio installs the app and a separate test APK, then shows the results.

The four bridge tests call embedded Python with synthetic SMS/chat messages. They check acknowledgements, emoji counting, capability replies, and safe failures. The screen test enters a chat message, sends it, checks Python's reply, and switches between tabs. SMS permission is unnecessary because the tests do not inject SMS broadcasts. The successful SMS handoff time appears in Logcat under `PythonBridgeTest`; it may include Python startup, depending on which test ran first.

Verification on October 2, 2026: all 13 unit tests passed in the project's Windows Python 3.11.9 environment. All five Android tests passed on the Pixel 7a running Android 17, including sending a chat message and switching tabs. The debug app/test APK builds and Android lint also passed. The optional timing command still benchmarks `handle_sms`, not chat, and its host timings are not Android latency or future LLM performance.

To run both application test classes from PowerShell, from `android-companion`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.abijith.expensetracker.PythonBridgeInstrumentedTest,com.abijith.expensetracker.ChatScreenInstrumentedTest'
```

Use a development device/emulator: Android test tooling installs development APKs and may restart the app. These tests cover the bridges and basic chat interaction. SMS broadcast delivery, permission dialogs, capture controls, storage recovery, and usability still need manual checks. Android Studio's generated sample tests are retained locally but excluded from Git; they do not verify app behavior. GitHub Actions is not configured yet; these suites run locally.

## Manual checks on your Pixel 7a

Use synthetic messages that contain no personal details or verification codes.

1. Install the updated APK using Android Studio **Run**. Send a message in **Chat**, check the help reply, then select **SMS capture** and check that it says `Milestone 2: on-phone Python handoff`.
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

There is no automatic replay or durable job queue. After troubleshooting a failed or interrupted handoff, enable capture and send a **new** synthetic SMS. Reopening the screen alone does not rerun old messages. Clearing history invalidates pending updates, so a late Python result cannot restore a cleared message. Pausing discards the result of a handoff already in flight; it cannot immediately terminate an already-running Python call.

## Understand the boundaries

Android wakes the broadcast receiver for a new SMS; this milestone does not run a continuous background service. The short offline acknowledgement runs on an executor within the receiver's `goAsync()` window. That window is limited: future LLM or network work must move to separately scheduled background work, rather than extending this receiver call.

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
| `SmsReceiver.kt` | Receives and combines SMS parts, saves the message, and requests a Python handoff on its background executor. |
| `PythonBridge.kt` | Starts embedded Python, builds input JSON, calls `handle_sms` or `handle_chat`, and validates their results. |
| `SmsStore.kt` | Atomically stores capture state and bounded history in `noBackupFilesDir`; updates handoff results on the existing message and prevents stale updates after pause/clear. |

The Python entry point is [`app/src/main/python/orchestrator.py`](app/src/main/python/orchestrator.py). `handle_sms(payload_json)` validates input and returns a receipt; `handle_chat(payload_json)` validates input and returns built-in help. Both use only the standard library and make no expense decisions yet.
