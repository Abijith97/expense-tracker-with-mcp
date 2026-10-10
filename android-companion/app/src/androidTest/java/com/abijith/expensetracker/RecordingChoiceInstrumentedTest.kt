package com.abijith.expensetracker

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.abijith.expensetracker.ui.theme.FinanceTrackerCompanionTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingChoiceInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestActivity>()
    private val message = CapturedSms("demo-record", "DEMO", "Demo payment", 0, 1,
        classification = SmsClassification(ClassificationStatus.EXPENDITURE, "demo-attempt"))

    @Test fun switchDefaultsOffSavesExactMessageAndCanExcludeAgain() {
        val current = mutableStateOf(message)
        var calls = 0
        compose.setContent {
            FinanceTrackerCompanionTheme {
                ExpenditureRecordingChoice(current.value) { id, attempt, selected ->
                    assertEquals(message.id, id); assertEquals("demo-attempt", attempt)
                    calls++; current.value = current.value.copy(recordExpenditure = selected); true
                }
            }
        }
        compose.onNodeWithTag("record_expenditure_demo-record").assertIsOff().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("record_expenditure_demo-record").assertIsOn().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("record_expenditure_demo-record").assertIsOff()
        compose.onNodeWithText("Excluded from recording").assertExists()
        assertEquals(2, calls)
    }

    @Test fun failedSaveLeavesSwitchOffAndShowsRetryMessage() {
        compose.setContent {
            FinanceTrackerCompanionTheme { ExpenditureRecordingChoice(message) { _, _, _ -> false } }
        }
        compose.onNodeWithTag("record_expenditure_demo-record").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("record_expenditure_demo-record").assertIsOff().assertIsEnabled()
        compose.onNodeWithText("Could not save the choice.", substring = true).assertExists()
    }

    @Test fun otherAndFailedMessagesCannotBeSelected() {
        val current = mutableStateOf(message.copy(classification = message.classification!!.copy(status = ClassificationStatus.OTHER)))
        compose.setContent {
            FinanceTrackerCompanionTheme {
                ExpenditureRecordingChoice(current.value) { _, _, _ -> error("Must not submit") }
            }
        }
        compose.onNodeWithTag("record_expenditure_demo-record").assertDoesNotExist()
        compose.runOnIdle { current.value = message.copy(classification = message.classification!!.copy(status = ClassificationStatus.FAILED)) }
        compose.onNodeWithTag("record_expenditure_demo-record").assertDoesNotExist()
    }
}
