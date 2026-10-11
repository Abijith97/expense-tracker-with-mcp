# Method tests: Activity and SMS screen wiring

[Index and evidence legend](Home.md) | [Category review](Method-Tests-Category-Review.md)

Source: [MainActivity.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/MainActivity.kt). Evidence: [ChatScreen](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/ChatScreenInstrumentedTest.kt), [SmsHistory](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/SmsHistoryInstrumentedTest.kt), [CloudControls](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/CloudControlsInstrumentedTest.kt). Many isolated Compose tests use a debug host instead of MainActivity; they do not establish Activity callback wiring.

| Case / method | Project purpose | Test contract / evidence |
| --- | --- | --- |
| MA-01 `onCreate()` | Obtain store/ViewModel, observe states, assemble tabs/dialogs/callbacks | **I/A:** chat integration launches Activity and asserts send/tab behavior. **G:** explicit cloud/review/recording/clear callback integration with isolated dependencies. |
| MA-02 `onResume()` | Recheck permission after settings; pause/cancel on revocation | **I:** lifecycle occurs in Activity test. **G/M:** resume after synthetic permission revocation; assert store disabled, unfinished cloud cancelled, no upload. Use disposable emulator. |
| MA-03 private `confirmCategory()` | Dispatch store confirmation on IO dispatcher | **G:** actual Activity submission persists correct labels/message/attempt; store and isolated UI tests currently verify boundaries separately. |
| MA-04 private `editCategory()` | Dispatch version-checked label update on IO | **G:** actual Activity edit/cancel/stale-version integration; underlying store cases already A. |
| MA-05 private `chooseRecording()` | Dispatch inclusion choice persistence on IO | **G:** Activity switch updates real isolated store and survives reconstructed store; isolated switch/store cases already A. |
| MA-06 private `hasSmsPermission()` | Query current Android permission | **I:** onResume reads it. **G/M:** assert granted/denied branch outcomes via Activity, not reflection. |
| MA-07 private `enableCapture()` | Enable immediately if granted or request/rationalize permission | **G/M:** allow/deny/rationale/cancel paths set correct capture state and notices; never treat permission request as cloud upload consent. |
| MA-08 `SmsDebugScreen()` | Render capture controls, cards and paging | **A:** 26 synthetic cards, Show older messages reveals older copies without uploading. **G:** all pause/clear/settings/reclassify callbacks receive correct state/ID. |
| MA-09 private `PythonHandoffDetails()` | Render acknowledgement or safe pending/failure state | **I:** diagnostics rendering can execute it. **G:** explicit Pending/Acknowledged/Failed/Interrupted UI assertions with safe details. |
| MA-10 private `SmsPerformanceDetails()` | Render present timings without inventing missing ones | **I:** cards can execute it. **G:** every optional timing omitted/displayed as designed, correct units/precision, signed clock estimate not called pure latency. |
| MA-11 private `formatTimestamp()` | Format card timestamps for display | **I:** history cards render dates. **G:** locale/timezone-aware formatting with fixed fixture; avoid brittle hardcoded device-local date strings. |

Anonymous permission/dialog/pause/clear callbacks are owned by onCreate/enableCapture tests, not separate named-method inventory rows. **G/M:** Clear cancel leaves history unchanged; deliberate confirm removes only app copies and invalidates jobs. Never clear actual user data as test setup. Permission dialogs and the real receiver/worker chain remain manual/proposed coverage.
