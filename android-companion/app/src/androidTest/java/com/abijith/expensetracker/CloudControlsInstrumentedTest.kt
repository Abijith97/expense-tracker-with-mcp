package com.abijith.expensetracker

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
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
    fun costsAndTokensAppearAlongsideTimingsAndUnknownIsNotZero() {
        val known = LlmUsage(1000, 100, 200, 100, DEFAULT_CLASSIFIER_MODEL,
            "default", .000289, "2026-10-03")
        val category = mutableStateOf(SmsCategory(CategoryStatus.IDENTIFIED, "Transport", usage = known))
        compose.setContent {
            FinanceTrackerCompanionTheme {
                Column { SmsClassificationDetails(SmsClassification(ClassificationStatus.EXPENDITURE,
                    "demo-cost", elapsedMs = 30.0, apiElapsedMs = 12.0, usage = known,
                    category = category.value), false, {}) }
            }
        }
        compose.onNodeWithText("Classification tokens: input 1000, output 100, cached 200, cache writes 100").assertExists()
        compose.onNodeWithText("Classification calculated cost: USD 0.00028900").assertExists()
        compose.onNodeWithText("Category calculated cost: USD 0.00028900").assertExists()
        compose.onNodeWithText("Calculated cost this attempt: USD 0.00057800").assertExists()
        compose.onNodeWithText("Total analysis processing: 30.000 ms").assertExists()
        compose.onNodeWithText("Expenditure API round trip and validation: 12.000 ms").assertExists()
        compose.runOnIdle { category.value = SmsCategory(CategoryStatus.FAILED, errorCode = "timeout") }
        compose.onNodeWithText("Category calculated cost: Unavailable").assertExists()
        compose.onNodeWithText("Calculated cost this attempt: Unavailable (not assumed zero)").assertExists()
        compose.onNodeWithText("Classification: Expenditure (true)").assertExists()
    }

    @Test
    fun categorySuggestionUncertaintyAndFailureAppearSeparately() {
        val category = mutableStateOf(SmsCategory(CategoryStatus.IDENTIFIED, "Groceries", 2.5, 1.5))
        compose.setContent {
            FinanceTrackerCompanionTheme {
                Column {
                    SmsClassificationDetails(SmsClassification(ClassificationStatus.EXPENDITURE,
                        "demo-attempt", category = category.value), false, {})
                }
            }
        }
        compose.onNodeWithText("Classification: Expenditure (true)").assertExists()
        compose.onNodeWithText("Category: Groceries (suggested, not confirmed)").assertExists()
        compose.onNodeWithText("Category API round trip and validation: 1.500 ms").assertExists()
        compose.runOnIdle { category.value = SmsCategory(CategoryStatus.IDENTIFIED, "Not sure") }
        compose.onNodeWithText("Category: Not sure - needs review").assertExists()
        compose.runOnIdle { category.value = SmsCategory(CategoryStatus.FAILED, errorCode = "authentication") }
        compose.onNodeWithText("Category: Failed - API key rejected. Check your key.").assertExists()
        compose.onNodeWithText("Classification: Expenditure (true)").assertExists()
    }

    @Test
    fun nonExpenditureShowsCategorySkipped() {
        compose.setContent {
            FinanceTrackerCompanionTheme {
                Column { SmsClassificationDetails(SmsClassification(ClassificationStatus.OTHER,
                    "demo-attempt"), false, {}) }
            }
        }
        compose.onNodeWithText("Category: Skipped (not an expenditure)").assertExists()
    }

    @Test
    fun enableRequiresTwoDistinctKeysAndConsentAndSuccessfulSaveClearsBothInputs() {
        val state = mutableStateOf(CloudClassificationState())
        var enableCount = 0
        compose.setContent {
            FinanceTrackerCompanionTheme {
                CloudClassificationControls(state.value, onEnable = { classificationKey, categoryKey, model, consent ->
                    assertEquals("DEMO-CLASSIFICATION-KEY", classificationKey)
                    assertEquals("DEMO-CATEGORY-KEY", categoryKey)
                    assertEquals(DEFAULT_CLASSIFIER_MODEL, model)
                    assertEquals(true, consent)
                    enableCount++
                    state.value = CloudClassificationState(true)
                    true
                }, onDisable = { state.value = CloudClassificationState() })
            }
        }
        compose.onNodeWithTag("cloud_enable").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_classification_key").performTextInput("DEMO-CLASSIFICATION-KEY")
        compose.onNodeWithTag("cloud_enable").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_consent").performClick()
        compose.onNodeWithTag("cloud_enable").assertIsNotEnabled() // Missing second key.
        compose.onNodeWithTag("cloud_category_key").performTextInput("DEMO-CLASSIFICATION-KEY")
        compose.onNodeWithTag("cloud_enable").assertIsNotEnabled() // Same key cannot separate costs.
        compose.onNodeWithTag("cloud_category_key").performTextClearance()
        compose.onNodeWithTag("cloud_category_key").performTextInput("DEMO-CATEGORY-KEY")
        compose.onNodeWithTag("cloud_consent").performClick()
        compose.onNodeWithTag("cloud_enable").assertIsNotEnabled() // Both keys still require consent.
        compose.onNodeWithTag("cloud_consent").performClick()
        compose.onNodeWithTag("cloud_enable").performClick()
        assertEquals(1, enableCount)
        compose.onNodeWithTag("cloud_disable").performClick()
        compose.onNodeWithTag("cloud_enable").assertIsNotEnabled()
        compose.onNodeWithText("Cloud: Off").assertExists()
        // Re-accepting consent must not enable the form with previously entered secrets.
        compose.onNodeWithTag("cloud_consent").performClick()
        compose.onNodeWithTag("cloud_enable").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_classification_key").performTextInput("DEMO-CLASSIFICATION-KEY")
        compose.onNodeWithTag("cloud_enable").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_category_key").performTextInput("DEMO-CATEGORY-KEY")
        compose.onNodeWithTag("cloud_enable").performClick()
        assertEquals(2, enableCount)
    }
}
