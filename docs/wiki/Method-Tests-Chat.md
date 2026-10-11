# Method tests: in-app chat

[Index and evidence legend](Home.md) | [Python flow](Method-Tests-Python-Flow.md)

Sources: [ChatScreen.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/ChatScreen.kt), [ChatViewModel.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/ChatViewModel.kt). Evidence: [ChatScreenInstrumentedTest.kt](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/ChatScreenInstrumentedTest.kt) and [CategoryReviewUiInstrumentedTest.kt](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/CategoryReviewUiInstrumentedTest.kt).

| Case / method | Project purpose | Test contract / evidence |
| --- | --- | --- |
| CH-01 `ChatScreen()` | Render session conversation, send controls and pending/saved reviews | **A:** blank send disabled; typed message gets real packaged local help; tab switch retains reply. Review callback payload and saved labels display verified separately with stub persistence. |
| CH-02 `ChatViewModel.send()` | Trim/guard input, append user message and dispatch Python off UI thread | **A/I:** UI integration exercises valid send/reply. **G:** direct blank/busy/cleared guard, trimming, bounded 60-message history and executor-dispatch tests. |
| CH-03 private `completeReply()` | Append correlated assistant response and clear sending state | **A/I:** real help displayed after send. **G:** deterministic response/error outcomes, bounded history and late response after clear. |
| CH-04 `onCleared()` | Stop executor and suppress late updates | **G:** dispose ViewModel with pending fake response; assert shutdown and no appended late reply. Lifecycle execution alone is not this assertion. |

Existing `sendMessageReceiveHelpAndSwitchToSmsDiagnostics` launches MainActivity and uses real local Python, not a hosted model. Chat is session-only, survives screen rotation through ViewModel but is not durable chat history. Every valid message currently returns capability help; spending queries, free-text category validation, WhatsApp and MCP are not implemented in this chat route.
