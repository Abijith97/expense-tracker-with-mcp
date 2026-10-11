package com.abijith.expensetracker

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.util.concurrent.TimeUnit

/** Real telephony -> receiver -> WorkManager -> packaged Python -> MainActivity.
 * Only the HTTP transport is fake. Opt-in harness + disposable emulator required:
 * ordinary phone test runs skip these tests before touching any stored data.
 */
@RunWith(AndroidJUnit4::class)
class SmsSystemInstrumentedTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    // Gate BEFORE the Activity rule: skipped phone runs must not even launch the
    // real Activity (which initializes credentials and updates permission state).
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val token = InstrumentationRegistry.getArguments().getString("smsSystemRunToken").orEmpty()
                assumeTrue("Disposable emulator harness required",
                    token.matches(Regex("[a-f0-9]{32}")) &&
                        (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") ||
                            Build.MODEL.contains("sdk_gphone")))
                base.evaluate()
            }
        }
    }).around(compose)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var fixture: PyObject? = null
    private var active = false
    private lateinit var store: SmsStore
    private lateinit var runToken: String

    @Before fun prepareDisposableEmulator() {
        runToken = InstrumentationRegistry.getArguments().getString("smsSystemRunToken").orEmpty()
        assumeTrue("Run through ci/run_android_system_tests.py on a disposable emulator",
            runToken.matches(Regex("[a-f0-9]{32}")) &&
                (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") ||
                    Build.MODEL.contains("sdk_gphone")))
        // Host harness also checks ro.kernel.qemu and refuses additional devices.
        active = true
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} ${Manifest.permission.RECEIVE_SMS}").let {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
        }
        WorkManager.getInstance(context).cancelAllWork().result.get(15, TimeUnit.SECONDS)
        store = SmsStore.get(context)
        assertTrue(store.clear())
        assertTrue(store.setEnabled(true))
        CloudClassificationSettings.initialize(context)
        assertTrue(CloudClassificationSettings.disable())
        val receipt = PythonBridge.acknowledge(context, CapturedSms("system-warmup", "5550100", "Synthetic warmup", 0, 1))
        assertEquals(HandoffStatus.ACKNOWLEDGED, receipt.status)
        val python = Python.getInstance()
        val module = python.getModule("types").callAttr("ModuleType", "system_test_transport")
        val source = instrumentation.context.assets.open("system_test_transport.py").bufferedReader().use { it.readText() }
        python.getModule("builtins").callAttr("exec", source, module.get("__dict__"))
        fixture = module
        compose.activityRule.scenario.recreate() // Reflect the granted permission in the real Activity.
    }

    @After fun cleanup() {
        if (!active) return
        try {
            CloudClassificationSettings.disable()
            WorkManager.getInstance(context).cancelAllWork().result.get(15, TimeUnit.SECONDS)
            store.setEnabled(false)
            store.clear()
        } finally { fixture?.callAttr("uninstall") }
    }

    private fun receive(scenario: String): CapturedSms {
        fixture!!.callAttr("install", scenario)
        assertTrue(CloudClassificationSettings.enable("SYSTEM-TEST-CLASSIFICATION", "SYSTEM-TEST-CATEGORY",
            DEFAULT_CLASSIFIER_MODEL, consent = true))
        // The host driver injects a real emulated SMS only after the fake is installed.
        Log.i("SmsSystemTest", "SMS_SYSTEM_READY|$runToken|$scenario")
        compose.waitUntil(60_000) {
            store.state.value.messages.any { message ->
                message.body.endsWith(" Test:$scenario.") &&
                    message.classification?.status in setOf(ClassificationStatus.EXPENDITURE,
                        ClassificationStatus.OTHER, ClassificationStatus.FAILED)
            }
        }
        val message = store.state.value.messages.single { it.body.endsWith(" Test:$scenario.") }
        assertFalse("Must enter via telephony, not the in-app simulator", message.simulated)
        assertEquals("5550100", message.sender)
        assertEquals(HandoffStatus.ACKNOWLEDGED, message.pythonHandoff?.status)
        assertNotNull(message.pythonHandoff?.performance?.receiverToJsonReadyMs)
        assertNotNull(message.classification?.elapsedMs)
        return message
    }

    private fun assertCalls(vararg expected: String) {
        val calls = JSONArray(fixture!!.callAttr("calls_json").toString())
        assertEquals(expected.toList(), (0 until calls.length()).map { calls.getString(it) })
    }

    private fun showSms() {
        compose.onNodeWithText("SMS capture").performClick()
        // The diagnostic card is below settings in a LazyColumn; bring it into
        // composition before looking up its descendants.
        compose.onNodeWithTag("sms_history").performScrollToNode(hasText("Classification:", substring = true))
    }

    @Test fun bmtcPaymentCanBeReviewedRecordedAndReloaded() {
        val message = receive("happy")
        assertEquals(ClassificationStatus.EXPENDITURE, message.classification!!.status)
        assertEquals("utilities", message.classification.category?.name)
        assertEquals("transport", message.classification.category?.subCategory)
        assertCalls("expenditure_classification", "spending_category")
        compose.onNodeWithTag("review_yes_${message.id}").performScrollTo().performClick()
        compose.waitUntil(10_000) { store.state.value.messages.single().categoryReview != null }
        compose.onNodeWithTag("record_expenditure_${message.id}").performScrollTo().performClick()
        compose.waitUntil(10_000) { store.state.value.messages.single().recordExpenditure == true }
        // Fresh disk reader, not only the singleton; Activity recreation is not process death.
        val reloaded = SmsStore(context).state.value.messages.single()
        assertEquals("utilities", reloaded.categoryReview?.category)
        assertEquals("transport", reloaded.categoryReview?.subCategory)
        assertEquals(true, reloaded.recordExpenditure)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Confirmed category: utilities").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Selected for recording").performScrollTo().assertIsDisplayed()
        assertCalls("expenditure_classification", "spending_category") // Review never calls the model.
    }

    @Test fun nonExpenditureNeverCallsCategoryOrOffersRecording() {
        val message = receive("other")
        assertEquals(ClassificationStatus.OTHER, message.classification!!.status)
        assertNull(message.classification.category)
        assertCalls("expenditure_classification")
        showSms()
        compose.onNodeWithText("Classification: Other (false)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("record_expenditure_${message.id}").assertDoesNotExist()
        compose.onNodeWithTag("category_review_${message.id}").assertDoesNotExist()
    }

    @Test fun exhaustedTimeoutIsFailureNotOtherAndSmsIsRetained() {
        val message = receive("timeout")
        assertEquals(ClassificationStatus.FAILED, message.classification!!.status)
        assertEquals("timeout", message.classification.errorCode)
        assertEquals(3, message.classification.networkAttempts)
        assertCalls("expenditure_classification", "expenditure_classification", "expenditure_classification")
        showSms()
        compose.onNodeWithText("API request timed out. Retry when ready.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("record_expenditure_${message.id}").assertDoesNotExist()
        assertEquals(message.body, SmsStore(context).state.value.messages.single().body)
    }

    @Test fun transientTimeoutRecoversWithoutRepeatingCategory() {
        val message = receive("retry")
        assertEquals(ClassificationStatus.EXPENDITURE, message.classification!!.status)
        assertEquals(2, message.classification.networkAttempts)
        assertEquals(1, message.classification.category?.networkAttempts)
        assertCalls("expenditure_classification", "expenditure_classification", "spending_category")
        compose.onNodeWithTag("review_yes_${message.id}").performScrollTo().assertIsDisplayed()
    }

    @Test fun malformedBooleanIsRejectedWithoutCategoryCall() {
        val message = receive("malformed")
        assertEquals(ClassificationStatus.FAILED, message.classification!!.status)
        assertEquals("invalid_response", message.classification.errorCode)
        assertEquals(1, message.classification.networkAttempts)
        assertCalls("expenditure_classification")
        showSms()
        compose.onNodeWithText("No valid classification was returned. Retry when ready.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun categoryAuthenticationFailurePreservesExpenditure() {
        val message = receive("category_failure")
        assertEquals(ClassificationStatus.EXPENDITURE, message.classification!!.status)
        assertEquals(CategoryStatus.FAILED, message.classification.category?.status)
        assertEquals("authentication", message.classification.category?.errorCode)
        assertEquals(1, message.classification.category?.networkAttempts)
        assertCalls("expenditure_classification", "spending_category")
        showSms()
        compose.onNodeWithText("API key rejected. Check your key.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("record_expenditure_${message.id}").performScrollTo().assertIsDisplayed()
    }

    @Test fun uncertainCategoryAcceptsCustomLabelsWithoutAnotherCall() {
        val message = receive("uncertain")
        assertEquals("Not sure", message.classification!!.category?.name)
        compose.onNodeWithTag("review_category_${message.id}").performScrollTo().performTextInput("utilities")
        compose.onNodeWithTag("review_subcategory_${message.id}").performScrollTo().performTextInput("transport")
        compose.onNodeWithTag("review_submit_${message.id}").performScrollTo().performClick()
        compose.waitUntil(10_000) { store.state.value.messages.single().categoryReview != null }
        assertEquals("transport", SmsStore(context).state.value.messages.single().categoryReview?.subCategory)
        assertEquals("Not sure", store.state.value.messages.single().classification?.category?.name)
        assertCalls("expenditure_classification", "spending_category")
    }
}
