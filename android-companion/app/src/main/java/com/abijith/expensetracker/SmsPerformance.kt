package com.abijith.expensetracker

import android.os.SystemClock
import org.json.JSONObject

/** Monotonic durations retain sub-millisecond precision and include sleep. */
fun elapsedMs(startNanos: Long, endNanos: Long = SystemClock.elapsedRealtimeNanos()): Double =
    (endNanos - startNanos) / 1_000_000.0

/** Receiver measurements for this process only; never persist the clock origin. */
data class SmsReceptionTiming(
    val enteredAtNanos: Long,
    val receiverSetupMs: Double,
    val executorWaitMs: Double,
    val messagePreparationMs: Double,
    val initialSaveMs: Double,
    val smsTimestampToReceiverMs: Long,
)

/** Diagnostic timings only. No sender or message body is included. */
data class SmsPerformance(
    val receiverSetupMs: Double? = null,
    val executorWaitMs: Double? = null,
    val messagePreparationMs: Double? = null,
    val initialSaveMs: Double? = null,
    val bridgeWaitMs: Double = 0.0,
    val jsonEncodeMs: Double? = null,
    val receiverToJsonReadyMs: Double? = null,
    val pythonSetupMs: Double? = null,
    val runtimeStarted: Boolean = false,
    val pythonCallMs: Double? = null,
    val receiptValidationMs: Double? = null,
    val bridgeTotalMs: Double = 0.0,
    val receiverToHandoffMs: Double? = null,
    val smsTimestampToReceiverMs: Long? = null,
) {
    fun toJson(): JSONObject {
        val json = JSONObject().put("schema_version", 1)
            .put("bridge_wait_ms", bridgeWaitMs)
            .put("runtime_started", runtimeStarted)
            .put("bridge_total_ms", bridgeTotalMs)
        val optionalFields = mapOf(
            "receiver_setup_ms" to receiverSetupMs,
            "executor_wait_ms" to executorWaitMs,
            "message_preparation_ms" to messagePreparationMs,
            "initial_save_ms" to initialSaveMs,
            "json_encode_ms" to jsonEncodeMs,
            "receiver_to_json_ready_ms" to receiverToJsonReadyMs,
            "python_setup_ms" to pythonSetupMs,
            "python_call_ms" to pythonCallMs,
            "receipt_validation_ms" to receiptValidationMs,
            "receiver_to_handoff_ms" to receiverToHandoffMs,
        )
        optionalFields.forEach { (name, value) -> if (value != null) json.put(name, value) }
        smsTimestampToReceiverMs?.let { json.put("sms_timestamp_to_receiver_ms", it) }
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): SmsPerformance {
            require(json.getInt("schema_version") == 1)
            fun optional(name: String): Double? =
                if (json.has(name) && !json.isNull(name)) json.getDouble(name) else null
            return SmsPerformance(
                receiverSetupMs = optional("receiver_setup_ms"),
                executorWaitMs = optional("executor_wait_ms"),
                messagePreparationMs = optional("message_preparation_ms"),
                initialSaveMs = optional("initial_save_ms"),
                bridgeWaitMs = json.getDouble("bridge_wait_ms"),
                jsonEncodeMs = optional("json_encode_ms"),
                receiverToJsonReadyMs = optional("receiver_to_json_ready_ms"),
                pythonSetupMs = optional("python_setup_ms"),
                runtimeStarted = json.getBoolean("runtime_started"),
                pythonCallMs = optional("python_call_ms"),
                receiptValidationMs = optional("receipt_validation_ms"),
                bridgeTotalMs = json.getDouble("bridge_total_ms"),
                receiverToHandoffMs = optional("receiver_to_handoff_ms"),
                smsTimestampToReceiverMs = if (json.has("sms_timestamp_to_receiver_ms"))
                    json.getLong("sms_timestamp_to_receiver_ms") else null,
            )
        }
    }
}
