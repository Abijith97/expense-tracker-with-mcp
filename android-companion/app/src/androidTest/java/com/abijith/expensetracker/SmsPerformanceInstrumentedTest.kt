package com.abijith.expensetracker

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Use synthetic messages, without SMS permission, reception, or disk writes. */
@RunWith(AndroidJUnit4::class)
class SmsPerformanceInstrumentedTest {
    private fun demoSms(body: String, id: String = "demo-performance-001") = CapturedSms(
        id = id,
        sender = "DEMO-SENDER",
        body = body,
        sentAtMillis = 1_700_000_000_000L,
        receivedAtMillis = 1_700_000_000_100L,
    )

    @Test
    fun benchmarkJsonEncodingForShortLongAndUnicodeMessages() {
        val cases = mapOf(
            "short_160_chars" to "Demo SMS ".repeat(18).take(160),
            "long_2000_chars" to "Demo SMS ".repeat(300).take(2000),
            "unicode_240_code_points" to "\u20B9\uD83D\uDED2 ".repeat(80),
        )
        cases.forEach { (label, body) ->
            val sms = demoSms(body)
            repeat(20) { SmsPayload.encode(sms) }
            val durations = (0 until 100).map {
                val startedAt = SystemClock.elapsedRealtimeNanos()
                val encoded = SmsPayload.encode(sms)
                val duration = elapsedMs(startedAt)
                // Assertions are outside the measured serialization interval.
                val json = JSONObject(encoded)
                assertEquals(sms.id, json.getString("message_id"))
                assertEquals(body, json.getString("body"))
                assertEquals(1, json.getInt("schema_version"))
                assertEquals(sms.sentAtMillis, json.getLong("sent_at_ms"))
                assertEquals(sms.receivedAtMillis, json.getLong("received_at_ms"))
                assertEquals(sms.sender, json.getString("sender"))
                duration
            }
            report("json_encode_$label", durations)
        }
    }

    @Test
    fun benchmarkWarmBridgeStages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val body = "Demo purchase \u20B9120 \uD83D\uDED2"
        val first = PythonBridge.acknowledge(context, demoSms(body))
        assertEquals(HandoffStatus.ACKNOWLEDGED, first.status)
        Log.i(TAG, "first_observed_call=${first.performance!!.toJson()}")
        // The first observed call is cold only when runtime_started is true.
        val timings = (0 until 100).map { index ->
            val handoff = PythonBridge.acknowledge(context, demoSms(body, "demo-performance-$index"))
            assertEquals(HandoffStatus.ACKNOWLEDGED, handoff.status)
            val performance = handoff.performance!!
            assertFalse(performance.runtimeStarted)
            assertNull(performance.receiverToJsonReadyMs)
            assertNull(performance.smsTimestampToReceiverMs)
            performance
        }
        report("bridge_wait", timings.map { it.bridgeWaitMs })
        report("json_encode_in_bridge", timings.map { it.jsonEncodeMs!! })
        report("python_setup_warm", timings.map { it.pythonSetupMs!! })
        report("python_call", timings.map { it.pythonCallMs!! })
        report("receipt_validation", timings.map { it.receiptValidationMs!! })
        report("bridge_total", timings.map { it.bridgeTotalMs })
    }

    @Test
    fun storedTimingsPreservePrecisionAndSignedClockEstimate() {
        val original = SmsPerformance(
            receiverSetupMs = 0.125,
            executorWaitMs = 0.375,
            messagePreparationMs = 0.125,
            initialSaveMs = 0.125,
            bridgeWaitMs = 0.25,
            jsonEncodeMs = 0.125,
            receiverToJsonReadyMs = 1.125,
            pythonSetupMs = 0.125,
            runtimeStarted = true,
            pythonCallMs = 0.125,
            receiptValidationMs = 0.125,
            bridgeTotalMs = 0.75,
            receiverToHandoffMs = 1.75,
            smsTimestampToReceiverMs = -100L,
        )
        val json = original.toJson()
        assertEquals(original, SmsPerformance.fromJson(JSONObject(json.toString())))
        assertFalse(json.has("enteredAtNanos"))
        assertFalse(json.has("sender"))
        assertFalse(json.has("body"))
        assertEquals(SmsPerformance(), SmsPerformance.fromJson(SmsPerformance().toJson()))
    }

    private fun report(metric: String, samples: List<Double>) {
        assertTrue(samples.all { it.isFinite() && it >= 0.0 })
        val sorted = samples.sorted()
        val median = (sorted[49] + sorted[50]) / 2.0
        // 100 samples: nearest-rank 95th percentile is the 95th sorted value.
        Log.i(TAG, String.format(Locale.US,
            "metric=%s count=100 median_ms=%.6f p95_ms=%.6f", metric, median, sorted[94]))
    }

    companion object {
        private const val TAG = "SmsPerformanceTest"
    }
}
