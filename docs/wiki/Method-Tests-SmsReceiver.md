# Method tests: SMS receiver

[Index and evidence legend](Home.md) | [BMTC persona](Persona-BMTC-Expenditure.md)

Source: [SmsReceiver.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/SmsReceiver.kt). Android invokes this BroadcastReceiver for new ordinary SMS. It does not query the inbox. The historical component-only CI measured zero coverage for `onReceive()`.

**A: implemented and verified** in [SmsSystemInstrumentedTest](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/SmsSystemInstrumentedTest.kt): seven real emulated SMS enter this receiver and persist a non-simulated card with the synthetic sender, Python acknowledgement and receiver-to-JSON timing. This verifies single-part granted/enabled reception through the assembled worker/UI flow. See [system-test results](System-Tests-Emulator.md). The broader contracts below remain **G** where not covered—especially multipart decoding, duplicate telephony broadcasts, denied/revoked permissions and asynchronous-error completion.

| Case / method | Project purpose | Arrange / expected assertion |
| --- | --- | --- |
| R-01 `onReceive()` | Decode and dispatch a new message | Supply valid synthetic multipart PDUs with granted permission/enabled isolated store; assert joined body, sender, timestamps, Pending handoff and one shared-processor invocation. No live API calls. |
| R-02 `onReceive()` | Reject irrelevant/invalid broadcasts | Wrong action, denied permission, paused capture, missing parts or blank body: no capture, handoff or scheduling. |
| R-03 `onReceive()` | Avoid duplicate cards | Deliver identical sender/timestamp/body twice; assert one card. Change timestamp/body and assert distinct captures. |
| R-04 `onReceive()` | Complete short asynchronous reception | Assert asynchronous completion on success and error; local dispatch does not execute hosted inference in the broadcast path. Inject dependencies or use a controlled integration harness; the receiver currently has no injectable processor. |
| R-05 `hasSmsPermission()` (private) | Guard access before decoding and on executor | Exercise granted/denied state through `onReceive()`, including revocation before executor work. Assert no message-content logging on failure. |

Run permission/reception checks on a disposable emulator. Do not revoke the user's real app permission, inject private SMS, write the real inbox or call private methods through reflection just to improve a number. A simulator bypasses this receiver. Actual carrier delivery/closed-screen behavior remains **M**, not established by synthetic broadcasts.
