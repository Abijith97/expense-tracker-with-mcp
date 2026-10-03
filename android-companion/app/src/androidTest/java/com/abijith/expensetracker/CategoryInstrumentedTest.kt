package com.abijith.expensetracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.PyException
import com.chaquo.python.Python
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real packaged Python and fixed-workflow contracts; never use a real key. */
@RunWith(AndroidJUnit4::class)
class CategoryInstrumentedTest {
    private val message = CapturedSms("demo-category", "DEMO-SENDER", "Demo purchase", 0, 1)

    @Test
    fun missingKeyCategoryFailurePreservesTheExpenditure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expenditure = SmsClassification(ClassificationStatus.EXPENDITURE, "demo-attempt", apiElapsedMs = 1.0)
        val result = withCategoryIfExpenditure(expenditure, { true }) {
            PythonBridge.identifyCategory(context, message,
                CloudCredentials("", "", DEFAULT_CLASSIFIER_MODEL, "demo-session"))
        }
        assertEquals(ClassificationStatus.EXPENDITURE, result.status)
        assertEquals(CategoryStatus.FAILED, result.category!!.status)
        assertEquals("configuration", result.category.errorCode)
        assertNull(result.category.name)
        assertTrue(result.category.apiElapsedMs!! >= 0)
        assertTrue(result.category.elapsedMs!! >= 0)
        assertFalse(result.toJson().toString().contains(message.body))
    }

    @Test
    fun packagedVocabularyMatchesKotlinAndParserPreservesKnownCategories() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PythonBridge.acknowledge(context, message)
        val module = Python.getInstance().getModule("category_identifier")
        val json = Python.getInstance().getModule("json")
        assertEquals(SPENDING_CATEGORIES, module.get("CATEGORIES")!!.asList().map { it.toString() }.toSet())
        SPENDING_CATEGORIES.forEach { name ->
            val response = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{\"category\":\"$name\"}"}]}]}"""
            assertEquals(name, module.callAttr("parse_response", json.callAttr("loads", response)).toString())
        }
        val invalid = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{\"category\":\"PRIVATE-UNKNOWN\"}"}]}]}"""
        try {
            module.callAttr("parse_response", json.callAttr("loads", invalid))
            fail("Unknown categories must be rejected")
        } catch (error: PyException) {
            assertFalse(error.message.orEmpty().contains("PRIVATE-UNKNOWN"))
        }
    }

    @Test
    fun onlyExpendituresWithCurrentConsentEnterTheCategoryStage() {
        var calls = 0
        val identify = { calls++; SmsCategory(CategoryStatus.IDENTIFIED, "Groceries") }
        listOf(ClassificationStatus.OTHER, ClassificationStatus.FAILED).forEach { status ->
            val original = SmsClassification(status, "demo-attempt")
            assertEquals(original, withCategoryIfExpenditure(original, { fail("Must not check next stage"); false }, identify))
        }
        assertEquals(0, calls)
        val expenditure = SmsClassification(ClassificationStatus.EXPENDITURE, "demo-attempt")
        val stopped = withCategoryIfExpenditure(expenditure, { false }, identify)
        assertEquals(ClassificationStatus.EXPENDITURE, stopped.status)
        assertEquals(CategoryStatus.INTERRUPTED, stopped.category!!.status)
        assertEquals(0, calls)
        val result = withCategoryIfExpenditure(expenditure, { true }, identify)
        assertEquals(1, calls)
        assertEquals("Groceries", result.category!!.name)
        val failure = withCategoryIfExpenditure(expenditure, { true }) {
            SmsCategory(CategoryStatus.FAILED, errorCode = "timeout")
        }
        assertEquals(ClassificationStatus.EXPENDITURE, failure.status)
        assertEquals("timeout", failure.category!!.errorCode)
    }

    @Test
    fun categoryResultRoundTripsAndLegacyClassificationStillLoads() {
        val category = SmsCategory(CategoryStatus.IDENTIFIED, "Not sure", 25.125, 24.375)
        val result = SmsClassification(ClassificationStatus.EXPENDITURE, "demo-attempt", category = category)
        assertEquals(result, SmsClassification.fromJson(JSONObject(result.toJson().toString())))
        val legacy = JSONObject("""{"status":"EXPENDITURE","attempt_id":"older-attempt"}""")
        assertNull(SmsClassification.fromJson(legacy).category)
    }
}
