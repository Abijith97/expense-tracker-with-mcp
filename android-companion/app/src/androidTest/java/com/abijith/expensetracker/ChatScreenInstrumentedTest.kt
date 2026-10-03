package com.abijith.expensetracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the visible chat controls and real Python response without an SMS. */
@RunWith(AndroidJUnit4::class)
class ChatScreenInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun sendMessageReceiveHelpAndSwitchToSmsDiagnostics() {
        compose.onNodeWithTag("chat_send").assertIsNotEnabled()
        compose.onNodeWithTag("chat_input").performTextInput("How do I use this finance tracker?")
        compose.onNodeWithTag("chat_send").performClick()

        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Your message reached the phone-side Python orchestrator.",
                substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Your message reached the phone-side Python orchestrator.",
            substring = true).assertIsDisplayed()

        // Tab navigation keeps chat in memory and leaves SMS controls accessible.
        compose.onNodeWithText("SMS capture").performClick()
        compose.onNodeWithText("Milestone 5: phone-side MCP tools").assertIsDisplayed()
        compose.onNodeWithText("Chat").performClick()
        compose.onNodeWithText("Your message reached the phone-side Python orchestrator.",
            substring = true).assertIsDisplayed()
    }
}
