# Method tests: cloud consent and result controls

[Index and evidence legend](Home.md) | [Cloud settings](Method-Tests-Cloud-Settings.md)

Source: [CloudClassificationControls.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/CloudClassificationControls.kt). Evidence: [CloudControlsInstrumentedTest.kt](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/CloudControlsInstrumentedTest.kt), using debug Compose host and stub enable/disable callbacks.

| Case / method | Project purpose | Test contract / evidence |
| --- | --- | --- |
| UI-01 `CloudClassificationControls()` | Collect two distinct keys and explicit upload consent | **A:** enable blocked for missing/equal keys or absent consent; successful enable clears both inputs; remembering off by default; remember/forget status and restoring/error controls correct. Callbacks do not enable real uploads. |
| UI-02 `SmsClassificationDetails()` | Render separate classifier/category statuses, errors, timings and estimates; expose explicit analysis action | **A:** Expenditure with known/uncertain/failed category; Other skips category; legacy flat labels do not invent subcategory; known/unknown costs and timing text. **G:** classify callback eligibility, repeated taps and every Waiting/Running/Interrupted status. |
| UI-03 private `LlmUsageDetails()` | Render per-stage token counts and estimated costs | **A/I:** `costsAndTokensAppearAlongsideTimingsAndUnknownIsNotZero` asserts classification/category tokens/costs through parent component. **G:** complete optional-cache/tier/model metadata display matrix. |

`enableRequiresTwoDistinctKeysAndConsentAndSuccessfulSaveClearsBothInputs` checks the form's callback arguments, not actual credential encryption. Vault/settings pages provide that separate evidence. Unknown cost must not appear as USD zero, and suggested labels must not be rendered as confirmed human labels.
