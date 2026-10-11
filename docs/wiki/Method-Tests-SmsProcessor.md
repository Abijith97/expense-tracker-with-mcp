# Method tests: shared SMS processor

[Index and evidence legend](Home.md) | [BMTC persona](Persona-BMTC-Expenditure.md)

Source: [SmsProcessor.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/SmsProcessor.kt). Existing assertions: [SmsProcessorInstrumentedTest.kt](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/SmsProcessorInstrumentedTest.kt). Use isolated cache-backed history and injected permission/acknowledgement/scheduling callbacks.

| Case / method | Project purpose | Test contract / evidence |
| --- | --- | --- |
| SP-01 `process()` | Save before handing off to Python | **A:** real/simulated input saves a Pending card before acknowledgement; uses real packaged local Python; persists Acknowledged on the same ID and invokes stub scheduling. Reception timings belong only to real supplied reception metadata. |
| SP-02 `process()` | Reject ineligible input | **A:** denied permission, duplicate ID, paused capture or stale generation prevents acknowledgement/scheduling. |
| SP-03 `process()` | Ignore late local results | **A:** pause or clear inside acknowledgement; late completion cannot restore a card or trigger cloud work. |
| SP-04 `process()` | Retain safe failure | **A:** acknowledgement throws private exception text; keep saved card marked Failed, without exposing that text in the result. |
| SP-05 `forContext()` | Wire Android permission, Python bridge and optional cloud scheduler | **A/I:** `actualLocalOnlySubmissionCreatesAnAcknowledgedCardWithoutCloudWork` goes through the real local-only simulator entry point. **G:** separately assert real `allowCloud=true` WorkManager wiring with fake inference and isolated settings. |

The explicit test `bothSourcesUsePackagedPythonAndSharedSchedulingWithoutInventingReceptionTimings` verifies the shared processor, not delivery through `SmsReceiver`. Callback assertions prove scheduling intent, not actual job execution. See the [worker gap](Method-Tests-SmsClassificationWorker.md).
