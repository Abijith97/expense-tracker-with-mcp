# Method tests: SMS simulation and release stubs

[Index and evidence legend](Home.md) | [Shared processor](Method-Tests-SmsProcessor.md)

Sources: debug [SmsSimulationControls.kt](../../android-companion/app/src/debug/java/com/abijith/expensetracker/SmsSimulationControls.kt), release [SmsSimulationControls.kt](../../android-companion/app/src/release/java/com/abijith/expensetracker/SmsSimulationControls.kt). Evidence: [SmsSimulation](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/SmsSimulationInstrumentedTest.kt), [SmsProcessor](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/SmsProcessorInstrumentedTest.kt).

| Case / method | Project purpose | Test contract / evidence |
| --- | --- | --- |
| SIM-01 debug `SimulatedSms.isValid()` | Validate sender and body bounds | **A:** blank/overlong inputs rejected; valid Unicode input permitted. **G:** exact sender/body maximum boundaries. |
| SIM-02 debug `SimulatedSms.create()` | Create unique labelled Pending test card | **A:** same text yields distinct IDs; simulated flag retained and no fabricated receiver timing. **G:** exact creation timestamp window assertions. |
| SIM-03 debug `submitSimulatedSms()` | Validate capture/permission and enter real shared processor | **A:** cloud-off real local handoff creates Acknowledged card; paused submission rejected; scheduling forbidden when allowCloud=false. Never sends SMS or writes inbox. |
| SIM-04 debug `SmsSimulationControls()` | Render editable samples and charge confirmation | **A:** paused/blank/overlong/restoring blocked; Unicode payload snapshot and cloud-off flag; cloud cancel invokes nothing; confirm invokes allowed callback; examples editable. |
| SIM-05 local debug `submit()` | Handle asynchronous callback, busy/error state and draft cleanup | **A/I:** stub UI callback results exercised. **G:** throwing callback, repeated submit while busy and failed-save draft retention checks. |
| SIM-06 release `SmsSimulationControls()` | Empty UI so simulator absent from release | **I:** release builds compile against stub. **G:** release UI test asserts no simulator controls. Build success alone is not UI assertion. |
| SIM-07 release `submitSimulatedSms()` | Reject synthetic entry in release | **G:** release-variant method test returns false without persistence/scheduling. Debug tests do not test this implementation. |

The synthetic permission wrapper is scoped to isolated tests; it does not grant permission to the real application. Simulation bypasses `SmsReceiver.onReceive()` and cannot establish real delivery, receiver timings or closed-app capture. Intentional live simulator inference is **M**, chargeable and requires consent; automated suites use no valid API key.
