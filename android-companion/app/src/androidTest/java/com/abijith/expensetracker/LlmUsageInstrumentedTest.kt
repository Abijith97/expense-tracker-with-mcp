package com.abijith.expensetracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Kwarg
import com.chaquo.python.Python
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reported counts are synthetic. Never calls OpenAI or accesses real SMS storage. */
@RunWith(AndroidJUnit4::class)
class LlmUsageInstrumentedTest {
    private val usage = LlmUsage(1000, 100, 200, 100, DEFAULT_CLASSIFIER_MODEL,
        "default", 0.000289, "2026-10-03")

    @Test
    fun usagePersistsWithBothStageTimingsAndOldResultsRemainReadable() {
        val category = SmsCategory(CategoryStatus.IDENTIFIED, "Transport", 15.0, 14.0, usage = usage)
        val original = SmsClassification(ClassificationStatus.EXPENDITURE, "demo-cost",
            DEFAULT_CLASSIFIER_MODEL, 30.0, 12.0, category = category, usage = usage)
        assertEquals(original, SmsClassification.fromJson(JSONObject(original.toJson().toString())))
        assertNull(SmsClassification.fromJson(JSONObject("""{"status":"OTHER","attempt_id":"old"}""")).usage)
        assertEquals(usage, LlmUsage.fromJson(JSONObject(usage.toJson().toString())))
    }

    @Test
    fun totalsRequireKnownCostsAndSkipOnlyUnrequestedCategory() {
        val first = SmsClassification(ClassificationStatus.OTHER, "demo-cost", usage = usage)
        assertEquals(.000289, analysisEstimatedCostUsd(first)!!, 1e-12)
        assertEquals(.000289, analysisEstimatedCostUsd(first.copy(status = ClassificationStatus.FAILED))!!, 1e-12)
        val expense = first.copy(status = ClassificationStatus.EXPENDITURE,
            category = SmsCategory(CategoryStatus.IDENTIFIED, "Transport", usage = usage))
        assertEquals(.000578, analysisEstimatedCostUsd(expense)!!, 1e-12)
        assertEquals(.000578, analysisEstimatedCostUsd(expense.copy(
            category = SmsCategory(CategoryStatus.FAILED, errorCode = "refused", usage = usage)))!!, 1e-12)
        assertNull(analysisEstimatedCostUsd(expense.copy(category = null)))
        assertNull(analysisEstimatedCostUsd(expense.copy(category = SmsCategory(CategoryStatus.FAILED))))
        assertNull(analysisEstimatedCostUsd(expense.copy(usage = null)))
        assertNull(analysisEstimatedCostUsd(first.copy(status = ClassificationStatus.RUNNING)))
    }

    @Test
    fun invalidOptionalTelemetryIsUnavailableNotAnInferenceFailure() {
        val invalid = listOf(
            """{"input_tokens":true,"output_tokens":1}""",
            """{"input_tokens":"1","output_tokens":1}""",
            """{"input_tokens":1.5,"output_tokens":1}""",
            """{"input_tokens":-1,"output_tokens":1}""",
            """{"input_tokens":10,"output_tokens":1,"cached_input_tokens":8,"cache_write_tokens":8}""",
            """{"input_tokens":10,"output_tokens":1,"estimated_cost_usd":-1}""",
        )
        invalid.forEach { raw ->
            assertNull(LlmUsage.fromJson(JSONObject(raw)))
            val saved = SmsClassification(ClassificationStatus.OTHER, "demo-cost").toJson()
                .put("usage", JSONObject(raw))
            val result = SmsClassification.fromJson(saved)
            assertEquals(ClassificationStatus.OTHER, result.status)
            assertNull(result.usage)
        }
        val countsOnly = LlmUsage.fromJson(JSONObject("""{"input_tokens":10,"output_tokens":1}"""))!!
        assertNull(countsOnly.estimatedCostUsd)
    }

    @Test
    fun packagedApiTelemetryCrossesTheBridgeForSuccessAndBillableFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val message = CapturedSms("demo-cost", "DEMO-SENDER", "Demo payment", 0, 1)
        PythonBridge.acknowledge(context, message)
        val python = Python.getInstance()
        val mock = python.getModule("unittest.mock")
        fun response(text: String, status: String = "completed") = JSONObject()
            .put("status", status).put("model", DEFAULT_CLASSIFIER_MODEL).put("service_tier", "default")
            .put("usage", JSONObject("""{"input_tokens":1000,"output_tokens":100,"input_tokens_details":{"cached_tokens":200,"cache_write_tokens":100}}"""))
            .put("output", org.json.JSONArray().put(JSONObject().put("type", "message")
                .put("content", org.json.JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))))
        val responses = org.json.JSONArray().put(response("""{"is_expenditure":true}"""))
            .put(response("""{"category":"Transport"}"""))
            .put(response("{}", "incomplete"))
        val fakeResponses = python.getModule("json").callAttr("loads", responses.toString())
        val api = mock.callAttr("Mock", Kwarg("side_effect", fakeResponses))
        // Helpers import their own transport references: patch both aliases, not just llm_api.
        val patches = listOf(
            mock.callAttr("patch", "expenditure_classifier.send_request", Kwarg("new", api)),
            mock.callAttr("patch", "category_identifier.send_request", Kwarg("new", api)),
            mock.callAttr("patch", "llm_api.urllib.request.build_opener", Kwarg("side_effect",
                python.getModule("builtins").callAttr("AssertionError", "HTTP forbidden in offline test"))),
        )
        val credentials = CloudCredentials("DEMO-CLASSIFICATION", "DEMO-CATEGORY", DEFAULT_CLASSIFIER_MODEL, "demo-session")
        try {
            patches.forEach { it.callAttr("start") }
            val first = PythonBridge.classifySms(context, message, credentials, "demo-attempt")
            assertEquals(ClassificationStatus.EXPENDITURE, first.status)
            assertEquals(usage, first.usage)
            val category = PythonBridge.identifyCategory(context, message, credentials)
            assertEquals("Transport", category.name)
            assertEquals(usage, category.usage)
            assertEquals(.000578, analysisEstimatedCostUsd(first.copy(category = category))!!, 1e-12)
            val failed = PythonBridge.identifyCategory(context, message, credentials)
            assertEquals(CategoryStatus.FAILED, failed.status)
            assertEquals("incomplete_response", failed.errorCode)
            assertEquals(usage, failed.usage)
            assertTrue(failed.apiElapsedMs!! >= 0)
            assertEquals(3, api.get("call_count")!!.toInt())
        } finally { patches.reversed().forEach { it.callAttr("stop") } }
    }
}
