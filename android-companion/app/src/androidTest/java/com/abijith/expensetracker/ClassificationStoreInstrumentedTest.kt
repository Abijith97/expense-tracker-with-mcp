package com.abijith.expensetracker

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Isolated synthetic cache directory: never accesses the user's real SMS store. */
@RunWith(AndroidJUnit4::class)
class ClassificationStoreInstrumentedTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(target.cacheDir, "sms-store-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(target) {
        override fun getNoBackupFilesDir(): File = directory
    }
    private val message = CapturedSms("demo-store", "DEMO-SENDER", "Demo purchase", 0, 1)

    @After
    fun removeOnlyThisTestsSyntheticFiles() {
        listOf("sms_capture.json", "sms_capture.json.bak", "sms_capture.json.new").forEach {
            File(directory, it).delete()
        }
        directory.delete()
    }

    @Test
    fun duplicateAttemptsAndStaleResultsCannotResurrectPausedOrClearedSms() {
        val store = SmsStore(context)
        assertTrue(store.setEnabled(true))
        val generation = store.activeGeneration()!!
        assertTrue(store.append(message, generation))
        val pending = store.prepareClassification(message.id, generation, DEFAULT_CLASSIFIER_MODEL)!!
        assertNull(store.prepareClassification(message.id, generation, DEFAULT_CLASSIFIER_MODEL))
        assertFalse(store.finishClassification(message.id, generation,
            pending.copy(attemptId = "unrelated", status = ClassificationStatus.EXPENDITURE)))
        assertNotNull(store.startClassification(message.id, generation, pending.attemptId))
        assertTrue(store.canContinueClassification(message.id, generation, pending.attemptId))
        assertFalse(store.canContinueClassification(message.id, generation, "unrelated"))
        assertTrue(store.setEnabled(false))
        assertFalse(store.canContinueClassification(message.id, generation, pending.attemptId))
        assertEquals(ClassificationStatus.INTERRUPTED, store.state.value.messages.single().classification!!.status)
        assertFalse(store.finishClassification(message.id, generation, pending.copy(status = ClassificationStatus.EXPENDITURE)))
        assertTrue(store.setEnabled(true))
        val nextGeneration = store.activeGeneration()!!
        val next = store.prepareClassification(message.id, nextGeneration, DEFAULT_CLASSIFIER_MODEL)!!
        assertTrue(store.clear())
        assertFalse(store.canContinueClassification(message.id, nextGeneration, next.attemptId))
        assertFalse(store.finishClassification(message.id, nextGeneration, next.copy(status = ClassificationStatus.OTHER)))
        assertTrue(store.state.value.messages.isEmpty())
    }

    @Test
    fun suggestedCategoryPersistsOnTheOriginalMessage() {
        val store = SmsStore(context)
        assertTrue(store.setEnabled(true))
        val generation = store.activeGeneration()!!
        assertTrue(store.append(message, generation))
        val pending = store.prepareClassification(message.id, generation, DEFAULT_CLASSIFIER_MODEL)!!
        assertNotNull(store.startClassification(message.id, generation, pending.attemptId))
        val result = pending.copy(status = ClassificationStatus.EXPENDITURE,
            category = SmsCategory(CategoryStatus.IDENTIFIED, "Groceries", 12.125, 10.375))
        assertTrue(store.finishClassification(message.id, generation, result))
        assertEquals(result, SmsStore(context).state.value.messages.single().classification)
        assertEquals(message.id, SmsStore(context).state.value.messages.single().id)
    }

    @Test
    fun completedResultPersistsButAnAbandonedRunningAttemptIsInterrupted() {
        val store = SmsStore(context)
        assertTrue(store.setEnabled(true))
        val generation = store.activeGeneration()!!
        assertTrue(store.append(message, generation))
        val pending = store.prepareClassification(message.id, generation, DEFAULT_CLASSIFIER_MODEL)!!
        val result = pending.copy(status = ClassificationStatus.OTHER, elapsedMs = 12.125, apiElapsedMs = 10.375)
        assertTrue(store.finishClassification(message.id, generation, result))
        assertEquals(result, SmsStore(context).state.value.messages.single().classification)
        val next = store.prepareClassification(message.id, generation, DEFAULT_CLASSIFIER_MODEL)!!
        assertNotNull(store.startClassification(message.id, generation, next.attemptId))
        assertEquals(ClassificationStatus.INTERRUPTED, SmsStore(context).state.value.messages.single().classification!!.status)
    }
}
