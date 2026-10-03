package com.abijith.expensetracker

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.abijith.expensetracker.ui.theme.FinanceTrackerCompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Consent UI only: stub callbacks cannot enable uploads or touch credentials. */
@RunWith(AndroidJUnit4::class)
class CloudControlsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun enableRequiresKeyAndConsentAndSuccessfulSaveClearsTheInput() {
        val state = mutableStateOf(CloudClassificationState())
        var enableCount = 0
        compose.setContent {
            FinanceTrackerCompanionTheme {
                CloudClassificationControls(state.value, onEnable = { key, model, consent ->
                    assertEquals("DEMO-NOT-A-REAL-KEY", key)
                    assertEquals(DEFAULT_CLASSIFIER_MODEL, model)
                    assertEquals(true, consent)
                    enableCount++
                    state.value = CloudClassificationState(true)
                    true
                }, onDisable = { state.value = CloudClassificationState() })
            }
        }
        compose.onNodeWithTag("cloud_enable").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_key").performTextInput("DEMO-NOT-A-REAL-KEY")
        compose.onNodeWithTag("cloud_enable").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_consent").performClick()
        compose.onNodeWithTag("cloud_enable").performClick()
        assertEquals(1, enableCount)
        compose.onNodeWithTag("cloud_disable").performClick()
        compose.onNodeWithTag("cloud_enable").assertIsNotEnabled()
        compose.onNodeWithText("Cloud: Off").assertExists()
    }
}
