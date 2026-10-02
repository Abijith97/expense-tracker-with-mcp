# Android companion — milestone 2: Python handoff

This milestone passes each new captured SMS from Kotlin to Python running inside the Android APK, then displays Python's acknowledgement on the same message card. App version: `0.2.0-python-handoff` (`versionCode` 2).

Capture starts **off** on a fresh installation. When you enable it and grant Android's SMS permission, the app keeps the latest 20 newly received SMS messages in app-private local storage. Each entry contains the sender, body, SMS timestamp, capture timestamp, and handoff status. All ordinary SMS messages are captured while enabled; financial classification comes later.

The Python entry point currently validates the handoff and returns a receipt. It uses only Python's standard library: no extra Python packages, LLM, MCP, WhatsApp, network connection, or finance-database update. The phone needs no separate Python installation, and your laptop backend and ngrok can remain stopped.

## What happens for one SMS

1. Android delivers a new SMS to `SmsReceiver`.
2. Its background executor combines the SMS parts and saves one local message with `Python: Pending`.
3. `PythonBridge` starts the embedded Python runtime if needed and calls `orchestrator.handle_sms(...)` with a JSON payload.
4. Python validates the fields and returns a JSON receipt.
5. Kotlin checks the receipt and updates the **same saved message** to `Python: Acknowledged`.

The displayed receipt contains only `schema_version`, `received`, `message_id`, `character_count`, `python_version`, and `processed_at_utc`. `received: true` means Python accepted the handoff; it does **not** mean the SMS is a financial transaction. Sender and message body are not echoed in this receipt.

`Handoff time` measures the bridge call, including Python startup on the first call in an app process. It excludes time spent waiting for SMS delivery and the executor. Later calls can be quicker because Python is already started. Status/timing diagnostics may appear in Logcat; the Python function does not print, and message content is not logged.

## Open and build

1. In Android Studio, open this `android-companion` directory, rather than the repository root.
2. Let Gradle sync finish. Use Android Studio's configured Gradle JDK and install any requested Android SDK components. The app targets Android 17 / API 37, with Android 8 / API 26 as its minimum. This APK includes `arm64-v8a` for your Pixel 7a and `x86_64` for a 64-bit emulator.
3. Ensure Python 3.11 is available on the Windows build machine, for example through `py -3.11 --version`. Chaquopy uses it for build-time bytecode compilation; its major/minor version must match the embedded Python 3.11. No `pip install` is needed for this milestone.
4. Connect your phone and select it in Android Studio. Use **Run** to install and open the debug app. For a USB connection, enable USB debugging on your phone and approve its connection to your computer.

Run the update over your existing development installation using the same debug signing key. Its saved messages and capture setting should remain. **Do not uninstall or clear Android app storage to update:** those actions erase the local history. Messages captured before this update remain visible as `Python: Not processed`; they are not replayed automatically.

The project uses Chaquopy `17.0.0` with embedded Python `3.11`. Its generated Android Gradle plugin is `9.4.1`, newer than Chaquopy's documented tested range through `9.2.x`. The debug build and lint check passed locally with these versions; on-phone Python execution still needs the manual check below. The [Chaquopy Gradle guide](https://chaquo.com/chaquopy/doc/current/android.html) documents runtime versions, CPU architectures, and the `buildPython` setting if local Python discovery fails.

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

- Enable capture in the companion, then approve the SMS permission request.
- Send a plain SMS to your phone from another phone. Return to the companion to inspect the received entry and its Python acknowledgement.
- Pause capture to stop saving subsequent messages.
- Clear the captured history to remove the companion's copies. This does not delete messages from your phone's normal messaging app.
- You can also revoke SMS permission in Android's app settings. Return to the companion to see the updated permission status.

The app requests `RECEIVE_SMS` for Android's new-message broadcast. It does not become the default messaging app, read your existing inbox, send SMS, or upload messages. This milestone does not request `READ_SMS`, `SEND_SMS`, or `INTERNET` permission. Message bodies are not written to app logs, and the stored history is excluded from Android backup/transfer rules.

## Manual checks on your Pixel 7a

Use synthetic messages that contain no personal details or verification codes.

1. Install the updated APK using Android Studio **Run** and check that the screen says `Milestone 2: on-phone Python handoff`.
2. Enable capture and grant SMS permission if needed.
3. Send `Demo purchase: Rs 120 at Cafe Example.` as an ordinary SMS from another phone.
4. Check that one message card shows `Python: Acknowledged`, a handoff time, and a receipt with `received: true` and `python_version` starting with `3.11`. `Pending` may disappear too quickly to observe.
5. Send a second ordinary SMS containing Unicode, such as `Demo grocery purchase ₹120 🛒`. Check that it is also acknowledged. Python counts Unicode code points; an emoji can occupy more than one Kotlin UTF-16 character, so the bridge compares code-point counts.
6. Press Home, send a third SMS, and reopen the app. Check that the background handoff completed. No laptop service or backend connection is involved.

These checks are manual; no automated test code is added for this milestone.

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
| `MainActivity.kt` | Capture controls, permission request/status, message cards, and Python receipt/timing display. |
| `SmsReceiver.kt` | Receives and combines SMS parts, saves the message, and requests a Python handoff on its background executor. |
| `PythonBridge.kt` | Starts embedded Python, builds the input JSON, calls `handle_sms`, and validates and filters its receipt. |
| `SmsStore.kt` | Atomically stores capture state and bounded history in `noBackupFilesDir`; updates handoff results on the existing message and prevents stale updates after pause/clear. |

The Python entry point is [`app/src/main/python/orchestrator.py`](app/src/main/python/orchestrator.py). Its `handle_sms(payload_json)` function validates the versioned input and returns the receipt using only the standard library. It does not classify messages or make expense decisions yet.
