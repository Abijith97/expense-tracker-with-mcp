# Method tests: human review and recording intent

[Index and evidence legend](Home.md) | [BMTC persona](Persona-BMTC-Expenditure.md)

Sources: [CategoryReview.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/CategoryReview.kt), [CategoryReviewCard.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/CategoryReviewCard.kt), [ExpenditureRecordingChoice.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/ExpenditureRecordingChoice.kt). Persistence methods are inventoried on [SmsStore](Method-Tests-SmsStore.md).

Evidence: [CategoryReviewStore](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/CategoryReviewStoreInstrumentedTest.kt), [CategoryReviewUi](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/CategoryReviewUiInstrumentedTest.kt), [RecordingChoice](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/RecordingChoiceInstrumentedTest.kt).

| Case / method | Project purpose | Test contract / evidence |
| --- | --- | --- |
| V-01 `CategoryReview.toJson()` / `fromJson()` | Persist human labels, attempt ID and review timestamp | **A/I:** reload isolated store after confirmation/edit; labels/version survive separately from model/timings. **G:** unsupported/malformed saved-review fields matrix. |
| V-02 `validReviewLabel()` | Reject empty/overlong/control/Not sure human labels | **A/I:** invalid-label store/UI assertions and accepted 80-character boundary; trim valid custom labels. |
| V-03 `needsCategoryReview()` | Identify uncertain completed expenditure needing form | **A:** only unreviewed Expenditure with Identified Not sure/Not sure; failed/missing/interrupted category does not qualify. |
| V-04 `needsCategoryValidation()` | Identify eligible unreviewed suggestions | **A/I:** confident and uncertain pair confirmation succeeds; duplicate/noneligible/stale submissions fail. **G:** explicit full status truth table. |
| V-05 `CategoryReviewCard()` | Render Yes/No, empty correction form, examples and edit controls | **A:** required fields, exact IDs/attempt, trimming, Yes, No, retained model output, prefilled editing/cancel/save and failed-save retry. UI persistence callbacks are stubbed. |
| V-06 local `saveLabels()` | Invoke correlated confirm/edit callback asynchronously | **A/I:** successful/failed callback assertions through UI; version passed on edit. **G:** thrown exception/cancellation/busy double-tap assertions. |
| V-07 `ExpenditureRecordingChoice()` | Render independent inclusion switch | **A:** defaults off; include/exclude callback uses exact ID/attempt; failed save remains off with error; Other/Failed cannot be selected. |

`submitUsesExactMessageAndAttemptThenShowsSavedLabelsInChat` proves UI callback payload/display, not real `MainActivity` storage wiring. `customLabelsPersistWithoutReplacingModelOrTimingsAndWorkWhilePaused` proves isolated storage separately. **G:** integrate those boundaries in one Activity-to-store test. Confirming/editing and recording choice do not call an LLM, train weights or post a ledger expense.
