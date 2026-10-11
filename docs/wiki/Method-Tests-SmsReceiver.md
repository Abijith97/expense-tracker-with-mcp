# Method tests: SMS receiver

[Index and evidence legend](Home.md) | [BMTC persona](Persona-BMTC-Expenditure.md)

Source: [SmsReceiver.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/SmsReceiver.kt). Android invokes this BroadcastReceiver for new ordinary SMS. It does not query the inbox. All cases below are **G: proposed**, not implemented assertions. Prior CI measured zero coverage for `onReceive()`.

| Case / method | Project purpose | Arrange / expected assertion |
| --- | --- | --- |
| R-01 `onReceive()` | Decode and dispatch a new message | Supply valid synthetic multipart PDUs with granted permission/enabled isolated store; assert joined body, sender, timestamps, Pending handoff and one shared-processor invocation. No live API calls. |
| R-02 `onReceive()` | Reject irrelevant/invalid broadcasts | Wrong action, denied permission, paused capture, missing parts or blank body: no capture, handoff or scheduling. |
| R-03 `onReceive()` | Avoid duplicate cards | Deliver identical sender/timestamp/body twice; assert one card. Change timestamp/body and assert distinct captures. |
| R-04 `onReceive()` | Complete short asynchronous reception | Assert asynchronous completion on success and error; local dispatch does not execute hosted inference in the broadcast path. Inject dependencies or use a controlled integration harness; the receiver currently has no injectable processor. |
| R-05 `hasSmsPermission()` (private) | Guard access before decoding and on executor | Exercise granted/denied state through `onReceive()`, including revocation before executor work. Assert no message-content logging on failure. |

Run permission/reception checks on a disposable emulator. Do not revoke the user's real app permission, inject private SMS, write the real inbox or call private methods through reflection just to improve a number. A simulator bypasses this receiver. Actual carrier delivery/closed-screen behavior remains **M**, not established by synthetic broadcasts.
