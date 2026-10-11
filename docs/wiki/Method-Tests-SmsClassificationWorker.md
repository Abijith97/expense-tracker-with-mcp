# Method tests: background classification worker

[Index and evidence legend](Home.md) | [BMTC persona](Persona-BMTC-Expenditure.md)

Source: [SmsClassificationWorker.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/SmsClassificationWorker.kt). The table is the original proposed contract inventory; implemented system-test subsets are identified below it, and the remaining assertions are **G** gaps. Historical component-only CI measured zero coverage for `doWork()`. The newer system tests exercise the assembled job, while [Category](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/CategoryInstrumentedTest.kt), [InferenceRetry](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/InferenceRetryInstrumentedTest.kt) and [ClassificationStore](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/ClassificationStoreInstrumentedTest.kt) provide additional dependency-level evidence.

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

**A: assembled-worker evidence now exists** in [SmsSystemInstrumentedTest](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/SmsSystemInstrumentedTest.kt), verified in the [system-test CI run](System-Tests-Emulator.md). The actual receiver schedules this worker; only Python's HTTP boundary is fake. Assertions cover correct two-stage order/keys, Other skipping category, exhausted classification timeouts, successful transient retries, malformed booleans and permanent category failure preserving Expenditure. This implements the main W-03/W-04 behaviour and portions of W-06, not every contract in the proposed table. It does not directly assert WorkManager Result values, unique-work metadata, constraint transitions, scheduling exceptions or lifecycle races.

Remaining gaps need controlled worker/WorkManager infrastructure and isolated credentials/history. Never run proposals with real keys to chase coverage. Separate **M** checks are needed for offline-to-online wake-up and cold/background process behaviour; the running-app emulator checks do not prove those.
