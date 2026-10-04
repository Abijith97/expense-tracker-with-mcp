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

/** Only isolated synthetic files; no actual SMS history, API keys, or network. */
@RunWith(AndroidJUnit4::class)
class CategoryReviewStoreInstrumentedTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(target.cacheDir, "category-review-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(target) {
        override fun getNoBackupFilesDir(): File = directory
    }
    private val suggestion = SmsClassification(ClassificationStatus.EXPENDITURE, "demo-attempt",
        elapsedMs = 42.0, apiElapsedMs = 21.0,
        category = SmsCategory(CategoryStatus.IDENTIFIED, "Not sure", 12.0, 10.0, subCategory = "Not sure"))
    private val message = CapturedSms("demo-review", "DEMO-SENDER", "Demo clothing purchase", 0, 1,
        classification = suggestion, simulated = true)

    private fun populatedStore(): SmsStore = SmsStore(context).also {
        assertTrue(it.setEnabled(true))
        assertTrue(it.append(message, it.activeGeneration()!!))
    }

    @After fun removeOnlySyntheticFiles() {
        listOf("sms_capture.json", "sms_capture.json.bak", "sms_capture.json.new").forEach { File(directory, it).delete() }
        directory.delete()
    }

    @Test fun onlyUncertainCompletedExpendituresNeedReview() {
        assertTrue(message.needsCategoryReview())
        for (status in ClassificationStatus.entries.filter { it != ClassificationStatus.EXPENDITURE }) {
            assertFalse(message.copy(classification = suggestion.copy(status = status)).needsCategoryReview())
        }
        assertFalse(message.copy(classification = suggestion.copy(category = null)).needsCategoryReview())
        assertFalse(message.copy(classification = suggestion.copy(category = SmsCategory(CategoryStatus.FAILED))).needsCategoryReview())
        assertFalse(message.copy(classification = suggestion.copy(category = SmsCategory(CategoryStatus.INTERRUPTED))).needsCategoryReview())
        assertFalse(message.copy(classification = suggestion.copy(category = SmsCategory(CategoryStatus.IDENTIFIED,
            "utilities", subCategory = "transport"))).needsCategoryReview())
    }

    @Test fun customLabelsPersistWithoutReplacingModelOrTimingsAndWorkWhilePaused() {
        val store = populatedStore()
        assertTrue(store.setEnabled(false))
        assertTrue(store.confirmCategory(message.id, suggestion.attemptId, " fun ", " clothing "))
        val saved = SmsStore(context).state.value.messages.single()
        assertEquals("fun", saved.categoryReview!!.category)
        assertEquals("clothing", saved.categoryReview.subCategory)
        assertEquals(suggestion.attemptId, saved.categoryReview.attemptId)
        assertTrue(saved.categoryReview.confirmedAtMillis > 0)
        assertEquals(suggestion, saved.classification)
        assertEquals(message.body, saved.body)
        assertTrue(saved.simulated)
        assertFalse(saved.needsCategoryReview())
        assertFalse(store.confirmCategory(message.id, suggestion.attemptId, "utilities", "clothing"))
    }

    @Test fun invalidLabelsDoNotMutateHistory() {
        val store = populatedStore()
        for (invalid in listOf("", "  ", "Not sure", "not SURE", "a".repeat(81), "food\ntransport", "a\u0000b")) {
            assertFalse(store.confirmCategory(message.id, suggestion.attemptId, invalid, "clothing"))
            assertFalse(store.confirmCategory(message.id, suggestion.attemptId, "fun", invalid))
        }
        assertNull(store.state.value.messages.single().categoryReview)
        assertTrue(store.confirmCategory(message.id, suggestion.attemptId, "a".repeat(80), "clothing"))
    }

    @Test fun staleAttemptsUnknownIdsAndClearedMessagesCannotBeSubmitted() {
        val store = populatedStore()
        assertFalse(store.confirmCategory("missing", suggestion.attemptId, "fun", "clothing"))
        assertFalse(store.confirmCategory(message.id, "wrong-attempt", "fun", "clothing"))
        val next = store.prepareClassification(message.id, store.activeGeneration()!!, DEFAULT_CLASSIFIER_MODEL)!!
        assertFalse(store.confirmCategory(message.id, suggestion.attemptId, "fun", "clothing"))
        assertFalse(store.confirmCategory(message.id, next.attemptId, "fun", "clothing"))
        assertTrue(store.clear())
        assertFalse(store.confirmCategory(message.id, suggestion.attemptId, "fun", "clothing"))
        assertTrue(SmsStore(context).state.value.messages.isEmpty())
    }

    @Test fun reclassificationKeepsHumanFeedbackAndOlderCardsStillLoad() {
        val store = populatedStore()
        assertNull(SmsStore(context).state.value.messages.single().categoryReview)
        assertTrue(store.confirmCategory(message.id, suggestion.attemptId, "fun", "clothing"))
        val feedback = store.state.value.messages.single().categoryReview
        val generation = store.activeGeneration()!!
        val next = store.prepareClassification(message.id, generation, DEFAULT_CLASSIFIER_MODEL)!!
        assertTrue(store.finishClassification(message.id, generation,
            next.copy(status = ClassificationStatus.EXPENDITURE, category = suggestion.category)))
        assertEquals(feedback, SmsStore(context).state.value.messages.single().categoryReview)
        assertFalse(store.state.value.messages.single().needsCategoryReview())
    }

    @Test fun separateFormsSaveOnlyTheirCorrespondingSms() {
        val store = populatedStore()
        val other = message.copy(id = "demo-second", classification = suggestion.copy(attemptId = "second-attempt"))
        assertTrue(store.append(other, store.activeGeneration()!!))
        assertFalse(store.confirmCategory(other.id, suggestion.attemptId, "fun", "clothing"))
        assertTrue(store.confirmCategory(other.id, "second-attempt", "fun", "clothing"))
        assertNull(store.state.value.messages.find { it.id == message.id }!!.categoryReview)
        assertNotNull(store.state.value.messages.find { it.id == other.id }!!.categoryReview)
    }
}
