# Method tests: bounded inference retries

[Index and evidence legend](Home.md) | [Background worker](Method-Tests-SmsClassificationWorker.md)

Source: [InferenceRetry.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/InferenceRetry.kt). Evidence: [InferenceRetryInstrumentedTest.kt](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/InferenceRetryInstrumentedTest.kt). Calls, clock, sleeps and jitter are injectable; existing tests make no network calls or real backoff waits.

| Case / method | Project purpose | Test contract / evidence |
| --- | --- | --- |
| IR-01 `retryInference()` | Repeat only eligible transient failures | **A:** success once; timeout/network/service_unavailable recover with bounded exponential backoff; persistent failure capped at three requests (initial plus two retries). |
| IR-02 `retryInference()` | Respect permanent/deferred failures | **A:** authentication/configuration/quota/invalid/refused/retry_deferred do not retry. |
| IR-03 `retryInference()` | Recheck authorization before calls and during backoff | **A:** initially unauthorized never calls; withdrawal during wait prevents next call. **G:** stopped/interrupted sleeping thread restores interrupt flag and prevents request. |
| IR-04 `retryInference()` | Bound retry scheduling window | **A:** 240-second elapsed window stops new retries; zero retries supported. **G:** invalid maxRetries rejected and jitter clamps at 0/250 boundary. This is not a hard total socket deadline. |
| IR-05 local `result()` | Return result, attempted request count and nonnegative elapsed time | **A/I:** counts/timing and cancellation results asserted through retryInference; no need direct invocation. |

`categoryRetriesDoNotRepeatSuccessfulExpenditureCall` composes retry logic with the category gate and injected calls. It is not execution of the real WorkManager worker. Retry count persistence and unknown full cost after retry are separately asserted; final-response usage cannot account for possibly billed timed-out requests.
