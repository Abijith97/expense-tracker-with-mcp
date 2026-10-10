package com.abijith.expensetracker

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    @get:Rule val compose = createAndroidComposeRule<ComposeTestActivity>()

    @Test
    fun costsAndTokensAppearAlongsideTimingsAndUnknownIsNotZero() {
        val known = LlmUsage(1000, 100, 200, 100, DEFAULT_CLASSIFIER_MODEL,
            "default", .000289, "2026-10-03")
        val category = mutableStateOf(SmsCategory(CategoryStatus.IDENTIFIED, "utilities", usage = known, subCategory = "transport"))
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
        val category = mutableStateOf(SmsCategory(CategoryStatus.IDENTIFIED, "utilities", 2.5, 1.5, subCategory = "groceries"))
        compose.setContent {
            FinanceTrackerCompanionTheme {
                Column {
                    SmsClassificationDetails(SmsClassification(ClassificationStatus.EXPENDITURE,
                        "demo-attempt", category = category.value), false, {})
                }
            }
        }
        compose.onNodeWithText("Classification: Expenditure (true)").assertExists()
        compose.onNodeWithText("Category: utilities (suggested, not confirmed)").assertExists()
        compose.onNodeWithText("Sub-category: groceries (suggested, not confirmed)").assertExists()
        compose.onNodeWithText("Category API round trip and validation: 1.500 ms").assertExists()
        compose.runOnIdle { category.value = SmsCategory(CategoryStatus.IDENTIFIED, "Not sure", subCategory = "Not sure") }
        compose.onNodeWithText("Category: Not sure - needs review").assertExists()
        compose.onNodeWithText("Sub-category: Not sure - needs review").assertExists()
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
                CloudClassificationControls(state.value, onEnable = { classificationKey, categoryKey, model, consent, remember ->
                    assertEquals("DEMO-CLASSIFICATION-KEY", classificationKey)
                    assertEquals("DEMO-CATEGORY-KEY", categoryKey)
                    assertEquals(DEFAULT_CLASSIFIER_MODEL, model)
                    assertEquals(true, consent)
                    assertEquals(false, remember)
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

    @Test
    fun rememberingIsOptInAndRememberedStatusAndForgetActionAreVisible() {
        val state = mutableStateOf(CloudClassificationState())
        var forgotten = false
        compose.setContent {
            FinanceTrackerCompanionTheme {
                CloudClassificationControls(state.value, onEnable = { _, _, _, consent, remember ->
                    assertEquals(true, consent)
                    assertEquals(true, remember)
                    state.value = CloudClassificationState(true, remembered = true)
                    true
                }, onDisable = { forgotten = true; state.value = CloudClassificationState() })
            }
        }
        compose.onNodeWithTag("cloud_remember").assertIsOff()
        compose.onNodeWithTag("cloud_classification_key").performTextInput("DEMO-FIRST")
        compose.onNodeWithTag("cloud_category_key").performTextInput("DEMO-SECOND")
        compose.onNodeWithTag("cloud_consent").performClick()
        compose.onNodeWithTag("cloud_remember").performClick().assertIsOn()
        compose.onNodeWithTag("cloud_enable").performClick()
        compose.onNodeWithText("Cloud: Enabled - remembered on this device").assertExists()
        compose.onNodeWithText("Disable cloud and forget keys").assertExists()
        compose.onNodeWithTag("cloud_disable").performClick()
        assertEquals(true, forgotten)
        compose.onNodeWithTag("cloud_remember").assertIsOff()
    }

    @Test
    fun restorationBlocksEnableAndFailureOffersForgetRatherThanSilentlyUploading() {
        val state = mutableStateOf(CloudClassificationState(loading = true))
        compose.setContent {
            FinanceTrackerCompanionTheme {
                CloudClassificationControls(state.value, onEnable = { _, _, _, _, _ -> error("Unexpected enable") },
                    onDisable = { state.value = CloudClassificationState() })
            }
        }
        compose.onNodeWithText("Cloud: Restoring settings").assertExists()
        compose.onNodeWithTag("cloud_enable").assertIsNotEnabled()
        compose.runOnIdle { state.value = CloudClassificationState(error = "DEMO restore failed") }
        compose.onNodeWithText("DEMO restore failed").assertExists()
        compose.onNodeWithTag("cloud_forget").performClick()
        compose.onNodeWithText("DEMO restore failed").assertDoesNotExist()
        compose.onNodeWithText("Cloud: Off").assertExists()
    }

    @Test
    fun oldFlatCategoriesRemainLegacyWithoutInventingASubCategory() {
        compose.setContent {
            FinanceTrackerCompanionTheme {
                Column { SmsClassificationDetails(SmsClassification(ClassificationStatus.EXPENDITURE,
                    "demo-old", category = SmsCategory(CategoryStatus.IDENTIFIED, "Food & dining")), false, {}) }
            }
        }
        compose.onNodeWithText("Category: Food & dining (legacy suggestion, not confirmed)").assertExists()
        compose.onNodeWithText("Sub-category: Not recorded (older result)").assertExists()
    }
}
