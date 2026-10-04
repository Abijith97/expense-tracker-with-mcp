package com.abijith.expensetracker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.abijith.expensetracker.ui.theme.FinanceTrackerCompanionTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Stub persistence callbacks only; no model calls or real user-history changes. */
@RunWith(AndroidJUnit4::class)
class CategoryReviewUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val message = CapturedSms("demo-review", "DEMO", "Demo clothing purchase", 0, 1,
        classification = SmsClassification(ClassificationStatus.EXPENDITURE, "demo-attempt",
            category = SmsCategory(CategoryStatus.IDENTIFIED, "Not sure", subCategory = "Not sure")))

    @Test fun fieldsStartEmptyShowExamplesAndRequireTwoValidLabels() {
        compose.setContent {
            FinanceTrackerCompanionTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CategoryReviewCard(message) { _, _, _, _ -> error("Must not submit") }
                }
            }
        }
        compose.onNodeWithText("learning / engineering", substring = true).assertExists()
        for (tag in listOf("review_category_demo-review", "review_subcategory_demo-review")) {
            assertEquals("", compose.onNodeWithTag(tag).fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text)
        }
        compose.onNodeWithTag("review_submit_demo-review").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("review_category_demo-review").performScrollTo().performTextInput("fun")
        compose.onNodeWithTag("review_submit_demo-review").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("review_subcategory_demo-review").performScrollTo().performTextInput("Not sure")
        compose.onNodeWithTag("review_submit_demo-review").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("review_subcategory_demo-review").performScrollTo().performTextReplacement("clothing")
        compose.onNodeWithTag("review_submit_demo-review").performScrollTo().assertIsEnabled()
    }

    @Test fun submitUsesExactMessageAndAttemptThenShowsSavedLabelsInChat() {
        val messages = mutableStateOf(listOf(message))
        var calls = 0
        compose.setContent {
            FinanceTrackerCompanionTheme {
                ChatScreen(ChatState(), { false }, messages.value) { id, attempt, category, subCategory ->
                    calls++
                    assertEquals(message.id, id)
                    assertEquals("demo-attempt", attempt)
                    assertEquals("fun", category)
                    assertEquals("clothing", subCategory)
                    messages.value = listOf(message.copy(categoryReview = CategoryReview(category, subCategory, attempt, 1)))
                    true
                }
            }
        }
        compose.onNodeWithTag("review_category_demo-review").performScrollTo().performTextInput(" fun ")
        compose.onNodeWithTag("review_subcategory_demo-review").performScrollTo().performTextInput(" clothing ")
        compose.onNodeWithTag("review_submit_demo-review").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, calls)
        compose.onNodeWithText("Confirmed category: fun").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confirmed sub-category: clothing").assertExists()
        compose.onNodeWithTag("review_submit_demo-review").assertDoesNotExist()
    }

    @Test fun failedSubmissionKeepsDraftAndCanRetry() {
        var calls = 0
        compose.setContent {
            FinanceTrackerCompanionTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CategoryReviewCard(message) { _, _, _, _ -> calls++; false }
                }
            }
        }
        compose.onNodeWithTag("review_category_demo-review").performScrollTo().performTextInput("fun")
        compose.onNodeWithTag("review_subcategory_demo-review").performScrollTo().performTextInput("clothing")
        compose.onNodeWithTag("review_submit_demo-review").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Could not save.", substring = true).assertExists()
        compose.onNodeWithTag("review_category_demo-review").assertTextContains("fun")
        compose.onNodeWithTag("review_subcategory_demo-review").assertTextContains("clothing")
        compose.onNodeWithTag("review_submit_demo-review").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(2, calls)
    }
}
