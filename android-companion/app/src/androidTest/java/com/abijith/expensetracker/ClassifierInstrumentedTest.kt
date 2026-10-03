package com.abijith.expensetracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Packaged Python and Kotlin result contracts; deliberately no valid API key. */
@RunWith(AndroidJUnit4::class)
class ClassifierInstrumentedTest {
    private val message = CapturedSms("demo-classifier", "DEMO-SENDER",
        "Sent Rs.120 to Example Store.", 0, 1)

    @Test
    fun missingApiKeyIsFailureNeverOtherAndMakesNoHttpRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = PythonBridge.classifySms(context, message,
            CloudCredentials("", "", DEFAULT_CLASSIFIER_MODEL, "demo-session"), "demo-attempt")
        assertEquals(ClassificationStatus.FAILED, result.status)
        assertEquals("configuration", result.errorCode)
        assertEquals("demo-attempt", result.attemptId)
        assertTrue(result.elapsedMs!! >= 0)
        assertTrue(result.apiElapsedMs!! >= 0)
        assertFalse(result.toJson().toString().contains(message.body))
    }

    @Test
    fun packagedResponseParserPreservesTrueAndFalse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PythonBridge.acknowledge(context, message) // Starts only local Python.
        val module = Python.getInstance().getModule("expenditure_classifier")
        val jsonModule = Python.getInstance().getModule("json")
        listOf(true, false).forEach { decision ->
            val response = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{\"is_expenditure\":$decision}"}]}]}"""
            val parsed = jsonModule.callAttr("loads", response)
            assertEquals(decision, module.callAttr("parse_response", parsed).toBoolean())
        }
    }

    @Test
    fun classificationTimingFormatPersistsAndRunningBecomesInterrupted() {
        val result = SmsClassification(ClassificationStatus.EXPENDITURE, "demo-attempt",
            DEFAULT_CLASSIFIER_MODEL, 25.125, 24.375)
        assertEquals(result, SmsClassification.fromJson(JSONObject(result.toJson().toString())))
        assertEquals(ClassificationStatus.INTERRUPTED,
            SmsClassification.fromJson(result.copy(status = ClassificationStatus.RUNNING).toJson()).status)
    }

}
