# Method tests: payload, usage and timing

[Index and evidence legend](Home.md) | [BMTC persona](Persona-BMTC-Expenditure.md)

Sources: [SmsPayload.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/SmsPayload.kt), [SmsPerformance.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/SmsPerformance.kt), [LlmUsage.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/LlmUsage.kt). Evidence: [SmsPerformance](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/SmsPerformanceInstrumentedTest.kt), [LlmUsage](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/LlmUsageInstrumentedTest.kt), [InferenceRetry](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/InferenceRetryInstrumentedTest.kt).

| Case / method | Project purpose | Test contract / evidence |
| --- | --- | --- |
| UT-01 `SmsPayload.encode()` | Build version-1 SMS JSON boundary | **A:** short/long/Unicode body and exact ID/sender/timestamps preserved, schema version correct; existing benchmark checks values outside timed interval. |
| UT-02 `elapsedMs()` | Convert monotonic nanoseconds to duration | **I/A:** performance outputs finite/nonnegative through benchmarks. **G:** deterministic direct input conversion/negative-interval policy assertions. |
| UT-03 `SmsPerformance.toJson()` / `fromJson()` | Persist detailed stage timings | **A:** precision and signed SMS-clock estimate round-trip; optional empty timings load; private sender/body/raw enteredAtNanos not serialized. |
| UT-04 local `optional()` in timing parser | Restore optional timing values | **I:** empty/populated round-trips exercise helper. **G:** explicit missing/wrong-type/nonfinite fields policy checks through fromJson. |
| UT-05 `LlmUsage.toJson()` / `fromJson()` | Persist safe provider counts and optional estimate | **A:** current/legacy result round-trips; invalid optional telemetry unavailable rather than inference failure; counts-only usage not assumed free. |
| UT-06 local `count()` / `optionalCount()` | Require actual integer counts and preserve missing optional counts | **A/I:** invalid/missing telemetry tests exercise parsing. **G:** assert each input/cache count boundary and boolean/floating/string rejection individually. |
| UT-07 `analysisEstimatedCostUsd()` | Total only known required stage costs | **A:** Other needs only first stage; expenditure needs category cost; unknown costs and retried attempts return unavailable. Not an actual billing lookup. |

`benchmarkWarmBridgeStages` runs real packaged acknowledgement and reports medians/p95. It checks sane output, not a latency SLA. Emulator/coverage-instrumented timings are not phone benchmarks. Do not add all card timings together: totals overlap stages. Receiver timestamp difference includes clock/delivery/scheduling effects, not pure detection latency.
