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

/**
 * Kotlin/Python boundary. Called on the receiver's serialized background executor,
 * never on the UI thread. This step performs no network calls or finance actions.
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

    private fun failureSince(startedAt: Long) = PythonHandoff(
        status = HandoffStatus.FAILED,
        elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
    )
}
