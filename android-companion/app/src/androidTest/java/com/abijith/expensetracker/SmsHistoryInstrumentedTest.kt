package com.abijith.expensetracker

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.abijith.expensetracker.ui.theme.FinanceTrackerCompanionTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic state only: no SMS permission, real history, or cloud credentials. */
@RunWith(AndroidJUnit4::class)
class SmsHistoryInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun olderCardsAreAccessibleWithoutDeletingHistoryOrUploading() {
        val messages = (0 until 26).map { CapturedSms("demo-page-$it", "DEMO", "DEMO body $it", 0, 1) }
        compose.setContent {
            FinanceTrackerCompanionTheme {
                SmsDebugScreen(SmsCaptureState(messages = messages), CloudClassificationState(), false,
                    null, {}, {}, {}, {}, { _, _, _, _, _ -> error("Must not upload") }, {},
                    { error("Must not classify") })
            }
        }
        compose.onNodeWithText("Captured messages: 26 (no 20-message storage cap)").assertExists()
        compose.onNodeWithTag("sms_history").performScrollToNode(hasText("Show older messages (6 remaining)"))
        compose.onNodeWithText("Show older messages (6 remaining)").performClick()
        compose.onNodeWithTag("sms_history").performScrollToNode(hasText("DEMO body 25"))
        compose.onNodeWithText("DEMO body 25").assertExists()
        compose.onNodeWithText("Show older messages (6 remaining)").assertDoesNotExist()
    }
}
