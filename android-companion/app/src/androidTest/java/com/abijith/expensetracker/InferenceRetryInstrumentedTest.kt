package com.abijith.expensetracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Stubbed calls, sleeps and clocks; no network requests or real backoff waits. */
@RunWith(AndroidJUnit4::class)
class InferenceRetryInstrumentedTest {
    @Test fun successfulRequestIsNeverRepeated() {
        var calls = 0
        val run = retryInference({ true }, { calls++; "ok" }, { null },
            sleepMillis = { error("Must not wait") })
        assertEquals("ok", run.result)
        assertEquals(1, run.requests)
        assertEquals(1, calls)
    }

    @Test fun transientFailureRecoversWithExponentialBackoffAndBoundedAttempts() {
        for (code in listOf("timeout", "network", "service_unavailable")) {
            var calls = 0
            var clock = 0L
            val run = retryInference({ true }, { calls++; if (calls < 3) code else "ok" },
                { if (it == "ok") null else it }, nowMillis = { clock },
                sleepMillis = { clock += it }, jitterMillis = { 0 })
            assertEquals("ok", run.result)
            assertEquals(3, run.requests)
            assertEquals(3000.0, run.elapsedMs, 0.001)
            calls = 0
            val exhausted = retryInference({ true }, { calls++; code }, { it },
                sleepMillis = {}, jitterMillis = { 0 })
            assertEquals(code, exhausted.result)
            assertEquals(3, calls)
        }
    }

    @Test fun permanentAndHintedErrorsNeverRetry() {
        for (code in listOf("authentication", "access_denied", "configuration", "rate_limit_or_quota",
            "request_rejected", "invalid_response", "refused", "retry_deferred")) {
            val run = retryInference({ true }, { code }, { it }, sleepMillis = { error("Must not wait") })
            assertEquals(1, run.requests)
        }
    }

    @Test fun consentWithdrawalDuringBackoffPreventsAnotherRequest() {
        var authorized = true
        var calls = 0
        val run = retryInference({ authorized }, { calls++; "timeout" }, { it },
            sleepMillis = { authorized = false }, jitterMillis = { 0 })
        assertNull(run.result)
        assertEquals(1, calls)
        val neverStarted = retryInference({ false }, { error("Must not call") }, { _: String -> null })
        assertEquals(0, neverStarted.requests)
    }

    @Test fun retryWindowStopsSchedulingAndZeroRetriesAreSupported() {
        var clock = 0L
        val run = retryInference({ true }, { clock = RETRY_WINDOW_MS; "timeout" }, { it },
            nowMillis = { clock }, sleepMillis = { error("Must not wait") })
        assertEquals(1, run.requests)
        assertEquals("timeout", run.result)
        val disabled = retryInference({ true }, { "network" }, { it }, maxRetries = 0,
            sleepMillis = { error("Must not wait") })
        assertEquals(1, disabled.requests)
    }

    @Test fun categoryRetriesDoNotRepeatSuccessfulExpenditureCall() {
        var expenditureCalls = 0
        var categoryCalls = 0
        val first = retryInference({ true }, {
            expenditureCalls++
            SmsClassification(ClassificationStatus.EXPENDITURE, "demo")
        }, { it.errorCode })
        val analysed = withCategoryIfExpenditure(first.result!!, { true }) {
            val next = retryInference({ true }, {
                categoryCalls++
                if (categoryCalls == 1) SmsCategory(CategoryStatus.FAILED, errorCode = "timeout")
                else SmsCategory(CategoryStatus.IDENTIFIED, "utilities", subCategory = "transport")
            }, { it.errorCode }, sleepMillis = {}, jitterMillis = { 0 })
            next.result!!.copy(networkAttempts = next.requests)
        }
        assertEquals(1, expenditureCalls)
        assertEquals(2, categoryCalls)
        assertEquals(2, analysed.category!!.networkAttempts)
    }

    @Test fun retryCountsPersistAndNeverClaimCompleteCostFromOnlyTheFinalResponse() {
        val usage = LlmUsage(10, 1, 0, 0, DEFAULT_CLASSIFIER_MODEL, "default", 0.001, "2026-10-03")
        val result = SmsClassification(ClassificationStatus.OTHER, "demo", usage = usage, networkAttempts = 2)
        assertEquals(result, SmsClassification.fromJson(result.toJson()))
        assertNull(analysisEstimatedCostUsd(result))
        val category = SmsCategory(CategoryStatus.IDENTIFIED, "utilities", usage = usage,
            subCategory = "food", networkAttempts = 2)
        assertEquals(category, SmsCategory.fromJson(category.toJson()))
        assertNull(analysisEstimatedCostUsd(result.copy(status = ClassificationStatus.EXPENDITURE,
            networkAttempts = 1, category = category)))
    }
}
