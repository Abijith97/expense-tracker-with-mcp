package com.abijith.expensetracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.Kwarg
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
        val hierarchy = JSONObject(json.callAttr("dumps",
            Python.getInstance().getModule("category_examples").get("CATEGORY_HIERARCHY")!!).toString())
        assertEquals(SPENDING_CATEGORY_HIERARCHY, hierarchy.keys().asSequence().associateWith { name ->
            val values = hierarchy.getJSONArray(name)
            (0 until values.length()).map { values.getString(it) }.toSet()
        })
        SPENDING_CATEGORY_HIERARCHY.forEach { (name, subCategories) ->
            subCategories.forEach { sub ->
                val response = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{\"category\":\"$name\",\"sub_category\":\"$sub\"}"}]}]}"""
                // PyObject.get reads attributes, not dictionary items. Use the
                // same JSON boundary as production rather than treating a dict as a module.
                val result = JSONObject(json.callAttr("dumps",
                    module.callAttr("parse_response", json.callAttr("loads", response))).toString())
                assertEquals(name, result.getString("category"))
                assertEquals(sub, result.getString("sub_category"))
            }
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
        val identify = { calls++; SmsCategory(CategoryStatus.IDENTIFIED, "utilities", subCategory = "groceries") }
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
        assertEquals("utilities", result.category!!.name)
        assertEquals("groceries", result.category.subCategory)
        val failure = withCategoryIfExpenditure(expenditure, { true }) {
            SmsCategory(CategoryStatus.FAILED, errorCode = "timeout")
        }
        assertEquals(ClassificationStatus.EXPENDITURE, failure.status)
        assertEquals("timeout", failure.category!!.errorCode)
    }

    @Test
    fun categoryResultRoundTripsAndLegacyClassificationStillLoads() {
        val category = SmsCategory(CategoryStatus.IDENTIFIED, "Not sure", 25.125, 24.375, subCategory = "Not sure")
        val result = SmsClassification(ClassificationStatus.EXPENDITURE, "demo-attempt", category = category)
        assertEquals(result, SmsClassification.fromJson(JSONObject(result.toJson().toString())))
        val legacy = JSONObject("""{"status":"EXPENDITURE","attempt_id":"older-attempt"}""")
        assertNull(SmsClassification.fromJson(legacy).category)
        val oldFlat = SmsCategory.fromJson(JSONObject("""{"status":"IDENTIFIED","name":"Groceries","elapsed_ms":2.0}"""))
        assertEquals("Groceries", oldFlat.name)
        assertNull(oldFlat.subCategory)
        assertEquals(oldFlat, SmsCategory.fromJson(oldFlat.toJson()))
        val current = SmsCategory(CategoryStatus.IDENTIFIED, "learning", subCategory = "engineering")
        assertEquals(current, SmsCategory.fromJson(current.toJson()))
    }

    @Test
    fun wrongPairsAndIncompleteNewLabelsCannotBecomeIdentifiedCategories() {
        val invalid = listOf("learning" to "transport", "fun" to "health", "Not sure" to "food",
            "utilities" to "engineering", "utilities" to "Not sure")
        invalid.forEach { (name, sub) ->
            assertFalse(isSpendingCategoryPair(name, sub))
            try {
                SmsCategory(CategoryStatus.IDENTIFIED, name, subCategory = sub)
                fail("Invalid pair must be rejected")
            } catch (_: IllegalArgumentException) { /* Expected validation, no labels echoed. */ }
        }
        assertFalse(isSpendingCategoryPair("utilities", null))
        assertFalse(isSpendingCategoryPair(null, "transport"))
    }

    @Test
    fun bridgeRejectsMissingSubCategoryWrongPairsAndWrongTypesWithoutChangingExpenditure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PythonBridge.acknowledge(context, message)
        val mock = Python.getInstance().getModule("unittest.mock")
        val invalid = listOf(
            """{"category":"utilities"}""", """{"category":"Groceries"}""",
            """{"category":"learning","sub_category":"transport"}""",
            """{"category":"utilities","sub_category":true}""",
            """{"category":"Not sure","sub_category":"groceries"}""",
        )
        invalid.forEach { label ->
            val reply = JSONObject(label).put("schema_version", 1).put("message_id", message.id)
                .put("status", "identified").put("api_elapsed_ms", 1.0)
            val patch = mock.callAttr("patch", "orchestrator.identify_sms_category", Kwarg("return_value", reply.toString()))
            try {
                patch.callAttr("start")
                val result = withCategoryIfExpenditure(SmsClassification(ClassificationStatus.EXPENDITURE,
                    "demo-attempt"), { true }) {
                    PythonBridge.identifyCategory(context, message,
                        CloudCredentials("DEMO-FIRST", "DEMO-SECOND", DEFAULT_CLASSIFIER_MODEL, "demo-session"))
                }
                assertEquals(ClassificationStatus.EXPENDITURE, result.status)
                assertEquals(CategoryStatus.FAILED, result.category!!.status)
                assertNull(result.category.name)
                assertNull(result.category.subCategory)
            } finally { patch.callAttr("stop") }
        }
    }
}
