# Method tests: Kotlin/Python bridge

[Index and evidence legend](Home.md) | [BMTC persona](Persona-BMTC-Expenditure.md)

Source: [PythonBridge.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/PythonBridge.kt). Python is packaged in the APK. Local receipt/chat and hosted inference are different entry points.

Existing evidence: [PythonBridge](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/PythonBridgeInstrumentedTest.kt), [Classifier](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/ClassifierInstrumentedTest.kt), [Category](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/CategoryInstrumentedTest.kt), [CloudKeys](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/CloudKeysInstrumentedTest.kt), [LlmUsage](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/LlmUsageInstrumentedTest.kt), [SmsPerformance](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/SmsPerformanceInstrumentedTest.kt).

| Case / method | Project purpose | Test contract / evidence |
| --- | --- | --- |
| B-01 `acknowledge()` | Serialize SMS, start/reuse Python, validate local receipt | **A:** Unicode acknowledged, correlated ID/count/version/time, finite performance fields, rejected SMS gives Failed/no receipt. No HTTP required. |
| B-02 `classifySms()` | Call `classify_sms` with classifier key and validate boolean contract | **A:** missing key Failed not Other; dedicated key routing; usage survives bridge. **G:** mocked mismatched ID/schema, truthy values and malformed timing tested at this Kotlin boundary. |
| B-03 `identifyCategory()` | Call `identify_sms_category` with category key and validate pair | **A:** missing key safely fails; wrong/missing/string-typed labels rejected; exact dedicated key; usage for success/billable failure. |
| B-04 `replyToChat()` | Call local help entry point and validate correlated reply | **A:** valid chat gets packaged help without SMS permission; blank input returns safe failure. **G:** mismatched reply ID/schema and missing reply fields at Kotlin boundary. |
| B-05 `safeApiElapsedMs()` (private) | Keep finite nonnegative API timing only | **I:** error/telemetry bridge paths exercise timing conversion. **G:** inject absent/negative/nonfinite timing and assert unavailable, not fabricated duration. |
| B-06 `safeInferenceCode()` (private) | Expose only allowlisted error codes | **I:** known configuration/invalid responses appear safely. **G:** inject unknown/private provider code and assert generic processing, no echo. |
| B-07 local `finish()` in `acknowledge()` | Build receipt/performance outcome on success/failure | **A/I:** acknowledged/rejected calls and performance suite inspect outcomes; dedicated helper not called directly. |
| B-08 `chatFailureSince()` (private) | Return safe fallback with elapsed time | **A/I:** `invalidChatReturnsASafeFailure` asserts fallback text/status through `replyToChat()`. |

`packagedBridgePassesOnlyTheDedicatedKeyToEachHelperWithoutHttp` patches Python helpers and forbids HTTP. This proves APK bridge wiring, not hosted accuracy. Private/local helper cases should be exercised through public entry points; no reflection-only tests needed. Chaquopy/native interpreter internals are not JaCoCo coverage targets.
