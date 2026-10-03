package com.abijith.expensetracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Kwarg
import com.chaquo.python.Python
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic keys only. Mock both helpers and prohibit HTTP in the bridge test. */
@RunWith(AndroidJUnit4::class)
class CloudKeysInstrumentedTest {
    private val settings = CloudSettings() // Never touch real remembered credentials.
    @After
    fun forgetSyntheticCredentials() { settings.disable() }

    @Test
    fun settingsRejectMissingEqualOrMalformedKeysAndMissingConsent() {
        settings.disable()
        val invalid = listOf(
            "" to "DEMO-CATEGORY",
            "DEMO-CLASSIFICATION" to "",
            "DEMO-SAME" to " DEMO-SAME ",
            "DEMO CLASSIFICATION" to "DEMO-CATEGORY",
            "DEMO-CLASSIFICATION" to "DEMO\nCATEGORY",
        )
        invalid.forEach { (classificationKey, categoryKey) ->
            assertFalse(settings.enable(classificationKey, categoryKey,
                DEFAULT_CLASSIFIER_MODEL, true))
            assertNull(settings.session())
            assertFalse(settings.state.value.enabled)
        }
        assertFalse(settings.enable("DEMO-CLASSIFICATION", "DEMO-CATEGORY",
            DEFAULT_CLASSIFIER_MODEL, false))
        assertFalse(settings.enable("DEMO-CLASSIFICATION", "DEMO-CATEGORY", "", true))
        assertNull(settings.session())
    }

    @Test
    fun distinctKeysStayOutOfPublicStateAndOldSessionsLoseAccess() {
        assertTrue(settings.enable(" DEMO-CLASSIFICATION ", " DEMO-CATEGORY ",
            DEFAULT_CLASSIFIER_MODEL, true))
        val session = settings.session()!!
        val credentials = settings.credentialsFor(session)!!
        assertEquals("DEMO-CLASSIFICATION", credentials.classificationApiKey)
        assertEquals("DEMO-CATEGORY", credentials.categoryApiKey)
        val publicText = settings.state.value.toString() + credentials.toString()
        assertFalse(publicText.contains(credentials.classificationApiKey))
        assertFalse(publicText.contains(credentials.categoryApiKey))
        assertNull(settings.credentialsFor("unrelated-session"))
        assertTrue(settings.enable("DEMO-NEW-CLASSIFICATION", "DEMO-NEW-CATEGORY",
            DEFAULT_CLASSIFIER_MODEL, true))
        assertNull(settings.credentialsFor(session))
        val nextSession = settings.session()!!
        settings.disable()
        assertNull(settings.credentialsFor(nextSession))
        assertNull(settings.session())
        assertFalse(settings.state.value.enabled)
    }

    @Test
    fun packagedBridgePassesOnlyTheDedicatedKeyToEachHelperWithoutHttp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val message = CapturedSms("demo-key-routing", "DEMO-SENDER", "Demo purchase", 0, 1)
        PythonBridge.acknowledge(context, message)
        val python = Python.getInstance()
        val mock = python.getModule("unittest.mock")
        val builtins = python.getModule("builtins")
        val patches = listOf(
            mock.callAttr("patch", "llm_api.send_request",
                Kwarg("side_effect", builtins.callAttr("AssertionError", "HTTP forbidden in offline test"))),
            mock.callAttr("patch", "orchestrator.classify_expenditure",
                // Java-to-Python calls have no Python caller frame for implicit globals.
                Kwarg("return_value", builtins.callAttr("eval", "(True, 1.0)", builtins.callAttr("dict")))),
            mock.callAttr("patch", "orchestrator.identify_category",
                Kwarg("return_value", builtins.callAttr("eval", "({'category':'utilities','sub_category':'transport'}, 2.0)", builtins.callAttr("dict")))),
        )
        try {
            val mocked = patches.map { it.callAttr("start") }
            val credentials = CloudCredentials("DEMO-CLASSIFICATION", "DEMO-CATEGORY",
                DEFAULT_CLASSIFIER_MODEL, "demo-session")
            val expenditure = PythonBridge.classifySms(context, message, credentials, "demo-attempt")
            assertEquals(ClassificationStatus.EXPENDITURE, expenditure.status)
            val category = PythonBridge.identifyCategory(context, message, credentials)
            assertEquals(CategoryStatus.IDENTIFIED, category.status)
            assertEquals("utilities", category.name)
            assertEquals("transport", category.subCategory)
            mocked[1].callAttr("assert_called_once_with", message.body,
                credentials.classificationApiKey, credentials.model, Kwarg("metrics", mock.get("ANY")!!))
            mocked[2].callAttr("assert_called_once_with", message.body,
                credentials.categoryApiKey, credentials.model, Kwarg("metrics", mock.get("ANY")!!))
            mocked[0].callAttr("assert_not_called")
            val storedResults = expenditure.toJson().toString() + category.toJson().toString()
            assertFalse(storedResults.contains(credentials.classificationApiKey))
            assertFalse(storedResults.contains(credentials.categoryApiKey))
        } finally {
            patches.reversed().forEach { it.callAttr("stop") }
        }
    }
}
