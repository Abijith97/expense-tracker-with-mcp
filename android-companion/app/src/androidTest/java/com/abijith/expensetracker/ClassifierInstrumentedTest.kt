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
            CloudCredentials("", DEFAULT_CLASSIFIER_MODEL, "demo-session"), "demo-attempt")
        assertEquals(ClassificationStatus.FAILED, result.status)
        assertEquals("configuration", result.errorCode)
        assertEquals("demo-attempt", result.attemptId)
        assertTrue(result.elapsedMs!! >= 0)
        assertNull(result.apiElapsedMs)
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

    @Test
    fun packagedMcpServerInitializesListsToolsAndCallsHelp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PythonBridge.acknowledge(context, message)
        val server = Python.getInstance().getModule("phone_mcp_server").callAttr("PhoneMcpServer")
        try {
            val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"Android test","version":"1"}}}"""
            assertEquals("2025-11-25", JSONObject(server.callAttr("exchange", init).toString())
                .getJSONObject("result").getString("protocolVersion"))
            server.callAttr("exchange", """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            val listed = JSONObject(server.callAttr("exchange",
                """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").toString())
                .getJSONObject("result").getJSONArray("tools")
            assertEquals(setOf("get_help", "classify_financial_message"),
                (0 until listed.length()).map { listed.getJSONObject(it).getString("name") }.toSet())
            val help = JSONObject(server.callAttr("exchange",
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_help","arguments":{}}}""").toString())
                .getJSONObject("result")
            assertFalse(help.getBoolean("isError"))
            assertTrue(help.getJSONObject("structuredContent").getString("reply").contains("get_help MCP tool"))
        } finally {
            server.callAttr("close")
        }
    }

    @Test
    fun packagedMcpClientReturnsOfflineHelpWithoutCloudCredentials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reply = PythonBridge.replyToChat(context, "demo-mcp-chat", "DEMO-PRIVATE-QUERY")
        assertTrue(reply.succeeded)
        assertTrue(reply.reply.contains("get_help MCP tool"))
        assertTrue(reply.reply.contains("classify_financial_message MCP tool"))
        assertFalse(reply.reply.contains("DEMO-PRIVATE-QUERY"))
    }
}
