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
 * This step performs no network calls or finance actions.
 */
object PythonBridge {
    @Synchronized
    fun acknowledge(context: Context, message: CapturedSms): PythonHandoff {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            val request = JSONObject()
                .put("schema_version", 1)
                .put("message_id", message.id)
                .put("sender", message.sender)
                .put("body", message.body)
                .put("sent_at_ms", message.sentAtMillis)
                .put("received_at_ms", message.receivedAtMillis)

            val reply = Python.getInstance().getModule("orchestrator")
                .callAttr("handle_sms", request.toString()).toString()
            val result = JSONObject(reply)
            // Check the returned contract, including Unicode code points (Python
            // len differs from Kotlin String.length for emoji/supplementary text).
            require(result.getInt("schema_version") == 1)
            require(result.getBoolean("received"))
            require(result.getString("message_id") == message.id)
            require(result.getInt("character_count") == message.body.codePointCount(0, message.body.length))
            require(result.getString("python_version").isNotBlank())
            require(result.getString("processed_at_utc").isNotBlank())

            // Keep only known non-content fields even if Python gains more output later.
            val acknowledgement = JSONObject()
                .put("schema_version", 1)
                .put("received", true)
                .put("message_id", message.id)
                .put("character_count", result.getInt("character_count"))
                .put("python_version", result.getString("python_version"))
                .put("processed_at_utc", result.getString("processed_at_utc"))
            PythonHandoff(
                status = HandoffStatus.ACKNOWLEDGED,
                acknowledgement = acknowledgement.toString(2),
                elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
            )
        } catch (_: Exception) {
            // Chaquopy exception text/tracebacks can include arguments. Do not log them.
            failureSince(startedAt)
        } catch (_: LinkageError) {
            // A missing/incompatible native Python runtime must not lose the saved SMS.
            failureSince(startedAt)
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

    private fun failureSince(startedAt: Long) = PythonHandoff(
        status = HandoffStatus.FAILED,
        elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
    )
}
