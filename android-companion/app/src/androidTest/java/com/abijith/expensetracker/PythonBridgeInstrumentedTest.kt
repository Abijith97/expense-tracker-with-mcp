package com.abijith.expensetracker

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Execute the production Kotlin bridge and the Python packaged in the real APK.
 * Synthetic messages bypass SmsReceiver and SmsStore, so this test needs no SMS
 * permission and does not change captured history, capture settings, or the inbox.
 */
@RunWith(AndroidJUnit4::class)
class PythonBridgeInstrumentedTest {
    private fun demoSms(body: String) = CapturedSms(
        id = "demo-instrumented-001",
        sender = "DEMO-SENDER",
        body = body,
        sentAtMillis = 1_700_000_000_000L,
        receivedAtMillis = 1_700_000_000_100L,
    )

    @Test
    fun unicodeSmsIsAcknowledgedByPackagedPython() {
        // Arrange: construct an SMS containing a rupee sign and a shopping emoji.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sms = demoSms("Demo \u20B9120 \uD83D\uDED2")

        // Act: run the real Kotlin -> Python -> Kotlin path.
        val handoff = PythonBridge.acknowledge(context, sms)

        // Assert: check the receipt, including Python/Kotlin Unicode agreement.
        assertEquals(HandoffStatus.ACKNOWLEDGED, handoff.status)
        assertNotNull(handoff.acknowledgement)
        val receipt = JSONObject(handoff.acknowledgement!!)
        assertEquals(sms.id, receipt.getString("message_id"))
        assertEquals(1, receipt.getInt("schema_version"))
        assertTrue(receipt.getBoolean("received"))
        assertEquals(11, receipt.getInt("character_count"))
        assertTrue(receipt.getString("python_version").startsWith("3.11."))
        assertTrue(receipt.getString("processed_at_utc").isNotBlank())
        assertEquals(6, receipt.length())
        assertFalse(receipt.has("sender"))
        assertFalse(receipt.has("body"))
        assertFalse(handoff.acknowledgement!!.contains(sms.sender))
        assertFalse(handoff.acknowledgement!!.contains(sms.body))
        assertNotNull(handoff.elapsedMillis)
        assertTrue(handoff.elapsedMillis!! >= 0L)
        val performance = handoff.performance!!
        assertTrue(performance.jsonEncodeMs!! >= 0.0)
        assertTrue(performance.pythonSetupMs!! >= 0.0)
        assertTrue(performance.pythonCallMs!! >= 0.0)
        assertTrue(performance.receiptValidationMs!! >= 0.0)
        assertTrue(performance.bridgeTotalMs >= performance.jsonEncodeMs!!)
        // A synthetic bridge call cannot measure real SMS reception.
        assertNull(performance.receiverToJsonReadyMs)
        assertNull(performance.smsTimestampToReceiverMs)
        // This can include runtime startup; order of test methods is unspecified.
        Log.i("PythonBridgeTest", "Acknowledged handoff: ${handoff.elapsedMillis} ms")
    }

    @Test
    fun rejectedSmsReturnsFailureWithoutAReceipt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val invalidSms = demoSms("   ")

        val handoff = PythonBridge.acknowledge(context, invalidSms)

        assertEquals(HandoffStatus.FAILED, handoff.status)
        assertNull(handoff.acknowledgement)
        assertNotNull(handoff.elapsedMillis)
        assertTrue(handoff.elapsedMillis!! >= 0L)
    }

    @Test
    fun chatIsAnsweredByPackagedPythonWithoutSmsPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val result = PythonBridge.replyToChat(context, "demo-chat-001", "How do I use this tracker?")

        assertTrue(result.succeeded)
        assertTrue(result.reply.contains("SMS capture tab"))
        assertTrue(result.reply.contains("not available yet"))
        assertTrue(result.elapsedMillis >= 0L)
    }

    @Test
    fun invalidChatReturnsASafeFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val result = PythonBridge.replyToChat(context, "demo-chat-001", "   ")

        assertFalse(result.succeeded)
        assertEquals("Could not process this message with local Python. Please try again.", result.reply)
        assertTrue(result.elapsedMillis >= 0L)
    }
}
