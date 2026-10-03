package com.abijith.expensetracker

import android.content.Context
import android.os.SystemClock
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

enum class HandoffStatus { PENDING, ACKNOWLEDGED, FAILED, INTERRUPTED }

/** Persist only a safe acknowledgement, never an exception containing SMS text. */
data class PythonHandoff(
    val status: HandoffStatus,
    val acknowledgement: String? = null,
    val elapsedMillis: Long? = null,
    val performance: SmsPerformance? = null,
)

/** A user-visible chat reply, with no Python exception details. */
data class PythonChatReply(
    val reply: String,
    val succeeded: Boolean,
    val elapsedMillis: Long,
)

/**
 * Kotlin/Python boundary. SMS and chat use background executors, never the UI
 * thread. The synchronized entry points share one embedded Python runtime.
 * Capture acknowledgement and chat perform no network calls. Hosted
 * classification is a separate worker-only call without finance actions.
 */
object PythonBridge {
    /** Called by WorkManager only. Never hold the SMS/chat monitor during HTTP. */
    fun classifySms(context: Context, message: CapturedSms, credentials: CloudCredentials,
                    attemptId: String): SmsClassification {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        return try {
            synchronized(this) {
                if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
            }
            val reply = Python.getInstance().getModule("orchestrator").callAttr(
                "classify_sms", SmsPayload.encode(message), credentials.classificationApiKey, credentials.model).toString()
            val result = JSONObject(reply)
            require(result.getInt("schema_version") == 1)
            require(result.getString("message_id") == message.id)
            val status = result.getString("status")
            if (status == "classified") {
                // Reject truthy strings/numbers: the model must return an actual boolean.
                require(result.get("is_expenditure") is Boolean)
                val apiElapsedMs = result.getDouble("api_elapsed_ms")
                require(apiElapsedMs.isFinite() && apiElapsedMs >= 0)
                SmsClassification(
                    if (result.getBoolean("is_expenditure")) ClassificationStatus.EXPENDITURE else ClassificationStatus.OTHER,
                    attemptId, credentials.model, elapsedMs(startedAt), apiElapsedMs,
                    usage = LlmUsage.fromJson(result.optJSONObject("usage")))
            } else {
                require(status == "failed")
                val code = result.getString("error_code")
                val safeCode = safeInferenceCode(code)
                SmsClassification(ClassificationStatus.FAILED, attemptId, credentials.model,
                    elapsedMs(startedAt), apiElapsedMs = safeApiElapsedMs(result), errorCode = safeCode,
                    usage = LlmUsage.fromJson(result.optJSONObject("usage")))
            }
        } catch (_: Exception) {
            SmsClassification(ClassificationStatus.FAILED, attemptId, credentials.model,
                elapsedMs(startedAt), errorCode = "processing")
        } catch (_: LinkageError) {
            SmsClassification(ClassificationStatus.FAILED, attemptId, credentials.model,
                elapsedMs(startedAt), errorCode = "processing")
        }
    }

    /** Second worker-only stage. The caller must first establish Expenditure. */
    fun identifyCategory(context: Context, message: CapturedSms,
                         credentials: CloudCredentials): SmsCategory {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        return try {
            synchronized(this) {
                if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
            }
            val reply = Python.getInstance().getModule("orchestrator").callAttr(
                "identify_sms_category", SmsPayload.encode(message), credentials.categoryApiKey, credentials.model).toString()
            val result = JSONObject(reply)
            require(result.getInt("schema_version") == 1)
            require(result.getString("message_id") == message.id)
            if (result.getString("status") == "identified") {
                require(result.get("category") is String)
                val name = result.getString("category")
                require(name in SPENDING_CATEGORIES)
                val apiElapsedMs = result.getDouble("api_elapsed_ms")
                require(apiElapsedMs.isFinite() && apiElapsedMs >= 0)
                SmsCategory(CategoryStatus.IDENTIFIED, name, elapsedMs(startedAt), apiElapsedMs,
                    usage = LlmUsage.fromJson(result.optJSONObject("usage")))
            } else {
                require(result.getString("status") == "failed")
                SmsCategory(CategoryStatus.FAILED, elapsedMs = elapsedMs(startedAt),
                    apiElapsedMs = safeApiElapsedMs(result),
                    errorCode = safeInferenceCode(result.getString("error_code")),
                    usage = LlmUsage.fromJson(result.optJSONObject("usage")))
            }
        } catch (_: Exception) {
            SmsCategory(CategoryStatus.FAILED, elapsedMs = elapsedMs(startedAt), errorCode = "processing")
        } catch (_: LinkageError) {
            SmsCategory(CategoryStatus.FAILED, elapsedMs = elapsedMs(startedAt), errorCode = "processing")
        }
    }

    private fun safeApiElapsedMs(result: JSONObject): Double? =
        result.optDouble("api_elapsed_ms", Double.NaN).takeIf { it.isFinite() && it >= 0 }

    private fun safeInferenceCode(code: String): String = code.takeIf {
        it in setOf("configuration", "authentication", "access_denied", "request_rejected",
            "rate_limit_or_quota", "service_unavailable", "timeout", "network", "invalid_input",
            "invalid_response", "incomplete_response", "refused")
    } ?: "processing"

    fun acknowledge(
        context: Context,
        message: CapturedSms,
        reception: SmsReceptionTiming? = null,
    ): PythonHandoff {
        // Start before acquiring the same monitor used by chat: lock contention
        // must be visible, rather than silently omitted from the bridge timing.
        val requestedAt = SystemClock.elapsedRealtimeNanos()
        return synchronized(this) {
            val enteredAt = SystemClock.elapsedRealtimeNanos()
            var jsonEncodeMs: Double? = null
            var jsonReadyAt: Long? = null
            var pythonSetupMs: Double? = null
            var runtimeStarted = false
            var pythonCallMs: Double? = null
            var validationMs: Double? = null

            fun finish(status: HandoffStatus, acknowledgement: String? = null): PythonHandoff {
                val finishedAt = SystemClock.elapsedRealtimeNanos()
                val performance = SmsPerformance(
                    receiverSetupMs = reception?.receiverSetupMs,
                    executorWaitMs = reception?.executorWaitMs,
                    messagePreparationMs = reception?.messagePreparationMs,
                    initialSaveMs = reception?.initialSaveMs,
                    bridgeWaitMs = elapsedMs(requestedAt, enteredAt),
                    jsonEncodeMs = jsonEncodeMs,
                    receiverToJsonReadyMs = reception?.let { observed ->
                        jsonReadyAt?.let { elapsedMs(observed.enteredAtNanos, it) }
                    },
                    pythonSetupMs = pythonSetupMs,
                    runtimeStarted = runtimeStarted,
                    pythonCallMs = pythonCallMs,
                    receiptValidationMs = validationMs,
                    bridgeTotalMs = elapsedMs(requestedAt, finishedAt),
                    receiverToHandoffMs = reception?.let { elapsedMs(it.enteredAtNanos, finishedAt) },
                    smsTimestampToReceiverMs = reception?.smsTimestampToReceiverMs,
                )
                return PythonHandoff(status, acknowledgement,
                    elapsedMillis = performance.bridgeTotalMs.toLong(), performance = performance)
            }

            try {
                val encodeStartedAt = SystemClock.elapsedRealtimeNanos()
                val request = try { SmsPayload.encode(message) } finally {
                    jsonEncodeMs = elapsedMs(encodeStartedAt)
                }
                jsonReadyAt = SystemClock.elapsedRealtimeNanos()

                // Serialization is measured before startup, so an interpreter
                // startup cannot be mistaken for slow message-to-JSON conversion.
                val setupStartedAt = SystemClock.elapsedRealtimeNanos()
                try {
                    if (!Python.isStarted()) {
                        Python.start(AndroidPlatform(context.applicationContext))
                        runtimeStarted = true
                    }
                } finally { pythonSetupMs = elapsedMs(setupStartedAt) }

                val callStartedAt = SystemClock.elapsedRealtimeNanos()
                val reply = try {
                    Python.getInstance().getModule("orchestrator")
                        .callAttr("handle_sms", request).toString()
                } finally { pythonCallMs = elapsedMs(callStartedAt) }

                val validationStartedAt = SystemClock.elapsedRealtimeNanos()
                val acknowledgement = try {
                    val result = JSONObject(reply)
                    // Python len counts code points; Kotlin String.length counts
                    // UTF-16 units, which differ for emoji/supplementary text.
                    require(result.getInt("schema_version") == 1)
                    require(result.getBoolean("received"))
                    require(result.getString("message_id") == message.id)
                    require(result.getInt("character_count") == message.body.codePointCount(0, message.body.length))
                    require(result.getString("python_version").isNotBlank())
                    require(result.getString("processed_at_utc").isNotBlank())

                    // Persist only known, non-content receipt fields.
                    JSONObject()
                        .put("schema_version", 1)
                        .put("received", true)
                        .put("message_id", message.id)
                        .put("character_count", result.getInt("character_count"))
                        .put("python_version", result.getString("python_version"))
                        .put("processed_at_utc", result.getString("processed_at_utc"))
                        .toString(2)
                } finally { validationMs = elapsedMs(validationStartedAt) }
                finish(HandoffStatus.ACKNOWLEDGED, acknowledgement)
            } catch (_: Exception) {
                // Never include SMS content or Python exception details in logs.
                finish(HandoffStatus.FAILED)
            } catch (_: LinkageError) {
                finish(HandoffStatus.FAILED)
            }
        }
    }

    @Synchronized
    fun replyToChat(context: Context, messageId: String, body: String): PythonChatReply {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            val request = JSONObject()
                .put("schema_version", 1)
                .put("message_id", messageId)
                .put("body", body)
            val reply = Python.getInstance().getModule("orchestrator")
                .callAttr("handle_chat", request.toString()).toString()
            val result = JSONObject(reply)
            require(result.getInt("schema_version") == 1)
            require(result.getString("message_id") == messageId)
            require(result.getString("status") == "capabilities")
            require(result.getString("reply").isNotBlank())
            require(result.getString("processed_at_utc").isNotBlank())
            PythonChatReply(
                reply = result.getString("reply"),
                succeeded = true,
                elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
            )
        } catch (_: Exception) {
            chatFailureSince(startedAt)
        } catch (_: LinkageError) {
            chatFailureSince(startedAt)
        }
    }

    private fun chatFailureSince(startedAt: Long) = PythonChatReply(
        reply = "Could not process this message with local Python. Please try again.",
        succeeded = false,
        elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
    )

}
