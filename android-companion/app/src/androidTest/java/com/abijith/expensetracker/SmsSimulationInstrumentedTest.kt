package com.abijith.expensetracker

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.abijith.expensetracker.ui.theme.FinanceTrackerCompanionTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Simulator UI uses stub callbacks only: no actual SMS, real store, credentials, or paid calls. */
@RunWith(AndroidJUnit4::class)
class SmsSimulationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pausedBlankOverlongAndRestoringStatesCannotSubmit() {
        val enabled = mutableStateOf(false)
        val loading = mutableStateOf(false)
        compose.setContent {
            FinanceTrackerCompanionTheme {
                SmsSimulationControls(enabled.value, false, loading.value) { _, _, _ -> error("Must not submit") }
            }
        }
        compose.onNodeWithTag("simulate_sms_open").performClick()
        compose.onNodeWithTag("simulate_sms_submit").assertIsNotEnabled()
        compose.onNodeWithTag("simulate_sms_body").performTextInput("Demo SMS")
        compose.onNodeWithTag("simulate_sms_submit").assertIsNotEnabled()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithTag("simulate_sms_submit").assertIsEnabled()
        compose.runOnIdle { loading.value = true }
        compose.onNodeWithTag("simulate_sms_submit").assertIsNotEnabled()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithTag("simulate_sms_body").performTextReplacement(" ")
        compose.onNodeWithTag("simulate_sms_submit").assertIsNotEnabled()
        compose.onNodeWithTag("simulate_sms_body").performTextReplacement("x".repeat(12001))
        compose.onNodeWithTag("simulate_sms_submit").assertIsNotEnabled()
    }

    @Test
    fun cloudOffSendsEditableUnicodeSnapshotWithCloudSchedulingForbidden() {
        var received: Triple<String, String, Boolean>? = null
        compose.setContent {
            FinanceTrackerCompanionTheme {
                SmsSimulationControls(true, false, false) { sender, body, allowCloud ->
                    received = Triple(sender, body, allowCloud); true
                }
            }
        }
        compose.onNodeWithTag("simulate_sms_open").performClick()
        compose.onNodeWithTag("simulate_sms_sender").performTextReplacement("DEMO-TEST")
        compose.onNodeWithTag("simulate_sms_body").performTextInput("Demo groceries \uD83D\uDED2")
        compose.onNodeWithTag("simulate_sms_submit").performClick()
        compose.waitForIdle()
        assertEquals(Triple("DEMO-TEST", "Demo groceries \uD83D\uDED2", false), received)
        compose.onNodeWithTag("simulate_sms_confirm_cloud").assertDoesNotExist()
        assertEquals("", compose.onNodeWithTag("simulate_sms_body").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("simulate_sms_notice").assertTextContains("no cloud request was scheduled", substring = true)
    }

    @Test
    fun cloudSubmissionRequiresConfirmationAndCancelDoesNothing() {
        var calls = 0
        var allowCloud = false
        compose.setContent {
            FinanceTrackerCompanionTheme {
                SmsSimulationControls(true, true, false) { _, body, allowed ->
                    assertEquals("Demo purchase", body)
                    calls++; allowCloud = allowed; true
                }
            }
        }
        compose.onNodeWithTag("simulate_sms_open").performClick()
        compose.onNodeWithTag("simulate_sms_body").performTextInput("Demo purchase")
        compose.onNodeWithTag("simulate_sms_submit").performClick()
        compose.onNodeWithText("Allow this test's API charges?").assertExists()
        assertEquals(0, calls)
        compose.onNodeWithTag("simulate_sms_cancel_cloud").performClick()
        assertEquals(0, calls)
        compose.onNodeWithTag("simulate_sms_submit").performClick()
        compose.onNodeWithTag("simulate_sms_confirm_cloud").performClick()
        compose.waitForIdle()
        assertEquals(1, calls)
        assertTrue(allowCloud)
    }

    @Test
    fun exampleButtonsPopulateEditableSyntheticMessages() {
        compose.setContent {
            FinanceTrackerCompanionTheme {
                SmsSimulationControls(true, false, false) { _, _, _ -> error("Must not submit") }
            }
        }
        compose.onNodeWithTag("simulate_sms_open").performClick()
        compose.onNodeWithTag("simulate_sms_expense_example").performScrollTo().performClick()
        compose.onNodeWithTag("simulate_sms_body").assertTextContains(SimulatedSms.EXPENDITURE_EXAMPLE)
        compose.onNodeWithTag("simulate_sms_other_example").performScrollTo().performClick()
        compose.onNodeWithTag("simulate_sms_body").assertTextContains(SimulatedSms.OTHER_EXAMPLE)
        compose.onNodeWithTag("simulate_sms_submit").assertIsEnabled()
    }
}
