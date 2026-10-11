# Method tests: classification and category contracts

[Index and evidence legend](Home.md) | [BMTC persona](Persona-BMTC-Expenditure.md)

Sources: [SmsClassification.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/SmsClassification.kt), [SmsCategory.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/SmsCategory.kt). Constructors also validate category pairs, attempt counts and timing fields; these are contracts but not separately named methods.

Evidence: [Classifier](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/ClassifierInstrumentedTest.kt), [Category](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/CategoryInstrumentedTest.kt), [LlmUsage](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/LlmUsageInstrumentedTest.kt), [InferenceRetry](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/InferenceRetryInstrumentedTest.kt), [CloudControls](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/CloudControlsInstrumentedTest.kt).

| Case / method | Project purpose | Test contract / evidence |
| --- | --- | --- |
| C-01 `SmsClassification.toJson()` | Persist classification metadata, category, usage and attempt count | **A:** round-trip keeps result/timings/usage/counts; raw SMS/key not in result object. |
| C-02 `SmsClassification.fromJson()` | Restore result with restart compatibility | **A:** Running becomes Interrupted; legacy results load without invented category/usage; current result round-trips. |
| C-03 `classificationFailureMessage()` | Translate safe error codes for the card | **I/A:** UI assertions check authentication failure text and failed-category display. **G:** table-driven assertion for every allowlisted code plus null/unknown fallback. |
| C-04 `isSpendingCategoryPair()` | Validate current hierarchy | **A:** known Python/Kotlin vocabulary agrees; wrong combinations/missing labels rejected. It validates labels, not payment semantics. |
| C-05 `SmsCategory.toJson()` | Persist suggestion, errors, usage and timings | **A:** current/legacy results and retry counts round-trip. |
| C-06 `SmsCategory.fromJson()` | Restore suggestion without inventing missing legacy subcategory | **A:** legacy flat result keeps missing subcategory; current Not sure/Not sure and known pairs survive. |
| C-07 `withCategoryIfExpenditure()` | Gate second stage and preserve first-stage decision | **A:** Other/Failed never invoke category; consent false gives Interrupted category; true permits one call; category error preserves Expenditure. |

Concrete existing checks include `onlyExpendituresWithCurrentConsentEnterTheCategoryStage`, `wrongPairsAndIncompleteNewLabelsCannotBecomeIdentifiedCategories` and `categoryResultRoundTripsAndLegacyClassificationStillLoads`. A proposed malformed JSON matrix should assert safe rejection without guessing Other or Not sure. Real BMTC classification is **M**, not established by these mocked/parsing checks.
