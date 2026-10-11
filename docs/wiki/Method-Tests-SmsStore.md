# Method tests: SMS store

[Index and evidence legend](Home.md) | [BMTC persona](Persona-BMTC-Expenditure.md)

Source: [SmsStore.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/SmsStore.kt). This is atomic whole-file JSON diagnostic history, not an expense ledger. Evidence: [ClassificationStore](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/ClassificationStoreInstrumentedTest.kt), [SmsProcessor](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/SmsProcessorInstrumentedTest.kt), [CategoryReviewStore](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/CategoryReviewStoreInstrumentedTest.kt).

| Case / method | Project purpose | Test contract / evidence |
| --- | --- | --- |
| S-01 `setEnabled()` | Enable/pause and invalidate prior capture work | **A:** enable permits append; pause interrupts Pending/Running work and prevents stale application. **G:** explicit no-op enable and storage-error branch assertions. |
| S-02 `activeGeneration()` | Supply current enabled-capture token | **A/I:** suites use token for eligible work and assert old tokens cannot apply after pause/clear. **G:** dedicated disabled-state null assertion. |
| S-03 `append()` | Prepend unique captured card | **A:** reject duplicate/stale/paused input; retain 26 messages across reconstructed store. |
| S-04 `canHandOff()` | Check retained Pending message and token | **I:** processor pause/clear guards exercise it. **G:** direct truth table for missing ID, non-Pending state and wrong generation. |
| S-05 `completeHandoff()` | Save local Python receipt on original card | **A/I:** processor asserts successful receipt, pause interruption and no resurrection after clear. |
| S-06 `prepareClassification()` | Create unique Waiting attempt, reset inclusion decision | **A:** no duplicate Waiting/Running attempts; reclassification resets recording intent while preserving human labels. |
| S-07 `startClassification()` | Claim Waiting attempt as Running | **A:** matching ID/token/attempt starts; old retained Waiting work remains eligible after 26 messages. **G:** exhaustive mismatched-status/id cases. |
| S-08 `canContinueClassification()` | Guard subsequent requests | **A:** wrong attempt, pause and clear forbid continuation. |
| S-09 `finishClassification()` | Save correlated final result | **A:** unrelated attempt rejected; original ID/category/timings persist; paused/cleared results rejected. |
| S-10 `interruptClassifications()` | Mark unfinished cloud work interrupted | **G:** dedicated cancellation test; verify Waiting/Running change, completed results unchanged and persisted restart state. Pause tests instead exercise the private helper. |
| S-11 `confirmCategory()` | Save first human labels on eligible expenditure | **A:** custom/confident labels, trimming, invalid labels, stale attempts, duplicate submission and separate message IDs; model/timings unchanged. |
| S-12 `editCategory()` | Update human labels using review version | **A:** latest editor saves; stale timestamp/attempt cannot overwrite; suggestion unchanged. |
| S-13 `setRecordExpenditure()` | Persist inclusion choice independently | **A:** include/exclude survives reload/pause; wrong attempt, cleared and non-expenditure cards rejected; message/labels retained. |
| S-14 `interruptClassification()` (private) | Convert unfinished cloud states to Interrupted | **I:** exercised by pause; **G:** direct public-boundary test covering both unfinished and all completed states. |
| S-15 `clear()` | Remove local copies, advance capture token | **A:** reload empty; late results/annotations cannot restore old cards. This never deletes the phone inbox. |
| S-16 `readFromDisk()` (private) | Restore history and recover abandoned work | **A/I:** reconstructed stores preserve results/reviews/choices/26 cards; Running/Pending recover as Interrupted. **G:** corrupt/unsupported-version history and missing fields with safe fail-closed error. |
| S-17 `save()` (private) | Atomically persist and publish state | **I:** all persistence assertions exercise successful writes. **G:** inject I/O failure and assert capture stops, generation changes, unfinished work interrupted, prior valid file recoverable and no private error text. |
| S-18 `get()` | Return shared process-wide store | **I:** real chat app startup acquires it. **G:** concurrent callers obtain one instance without cross-context history confusion. |

Do not replace the singleton/store path with real user history during tests. Reconstruct isolated instances to test persistence; this is not a real process-kill/reboot test. For concrete existing method names, start with `duplicateAttemptsAndStaleResultsCannotResurrectPausedOrClearedSms`, `moreThanTwentyMessagesSurviveRestartAndOldPendingWorkIsStillEligible` and `savedLabelsAreEditableButStaleEditorsCannotOverwriteNewInput`.
