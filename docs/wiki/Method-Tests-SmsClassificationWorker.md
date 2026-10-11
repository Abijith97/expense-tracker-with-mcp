# Method tests: background classification worker

[Index and evidence legend](Home.md) | [BMTC persona](Persona-BMTC-Expenditure.md)

Source: [SmsClassificationWorker.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/SmsClassificationWorker.kt). All worker-specific cases below are **G**: proposed, not runnable checks in the current suite. Prior CI measured zero coverage for `doWork()`. Existing [Category](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/CategoryInstrumentedTest.kt), [InferenceRetry](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/InferenceRetryInstrumentedTest.kt) and [ClassificationStore](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/ClassificationStoreInstrumentedTest.kt) test its dependencies, not the assembled job.

| Case / method | Project purpose | Arrange / expected assertion |
| --- | --- | --- |
| W-01 `enqueue()` | Prepare network-constrained unique work | Enabled isolated session and retained SMS: one Waiting attempt; work input has IDs/tokens only; connected-network constraint and unique KEEP policy. No cloud session, paused capture or duplicate pending work: no new job. |
| W-02 `enqueue()` | Surface scheduling failure | Inject scheduler exception; original attempt becomes Failed/scheduling rather than remaining Waiting. |
| W-03 `doWork()` | Execute two-stage workflow | Fake classifier true, fake utilities/transport: start Running; each matching key used once; category after classification; final result on original SMS; successful WorkManager result. |
| W-04 `doWork()` | Skip category for Other | Fake false: one classification call, zero category calls, Other persisted. |
| W-05 `doWork()` | Handle missing/stale authorization | Missing input IDs -> failure. Revoked permission/invalid session -> interrupted/configuration failure where applicable. Missing/stale retained attempt -> safe no-op success. No API calls. |
| W-06 `doWork()` | Separate request failures | Exhausted classifier failure -> no category; exhausted category failure -> Expenditure preserved with failed category; Result.failure. Transient retries stay within per-stage policy. |
| W-07 local `canContinue()` | Recheck stop/session/permission/store token | Stop, pause, clear or withdraw consent between calls/backoff: no next request, no resurrection. Already-sent requests cannot be unsent. |
| W-08 `cancelAll()` | Cancel tagged jobs and interrupt stored work | Only tagged cloud jobs cancelled; Waiting/Running interrupted; completed history untouched. |

An implementation would need controlled worker dependencies/WorkManager test infrastructure and isolated credentials/history. Never run these proposals with real keys to chase coverage. Separate **M** checks are needed for real Android scheduling, offline-to-online wake-up and cold/background process behavior; mocked worker success does not prove those.
