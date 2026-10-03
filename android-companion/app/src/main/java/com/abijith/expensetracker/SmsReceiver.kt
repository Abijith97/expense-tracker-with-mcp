package com.abijith.expensetracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Receives new SMS only. It never queries the inbox or sends a message. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The earliest app-observable boundary, not the modem's receipt time.
        val enteredAtNanos = SystemClock.elapsedRealtimeNanos()
        val receivedAt = System.currentTimeMillis()
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!hasSmsPermission(context)) return

        val store = SmsStore.get(context)
        val generation = store.activeGeneration() ?: return
        val pendingResult = goAsync()
        val queuedAtNanos = SystemClock.elapsedRealtimeNanos()

        // Android gives this receiver a short execution window, not an always-on
        // service. This milestone adds only a short, local Python acknowledgement.
        // Do not add LLM/network work here: that needs separately scheduled work.
        executor.execute {
            val workerStartedAt = SystemClock.elapsedRealtimeNanos()
            try {
                if (!hasSmsPermission(context)) return@execute
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (parts.isNullOrEmpty()) return@execute
                val sender = parts.first().originatingAddress ?: "Unknown sender"
                val body = parts.joinToString(separator = "") { it.messageBody.orEmpty() }
                if (body.isBlank()) return@execute
                val sentAt = parts.first().timestampMillis

                // A stable key avoids duplicate copies of the same SMS broadcast.
                val fingerprint = "$sender\u0000$sentAt\u0000$body"
                val id = MessageDigest.getInstance("SHA-256")
                    .digest(fingerprint.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                val message = CapturedSms(
                    id, sender, body, sentAt, receivedAt,
                    pythonHandoff = PythonHandoff(HandoffStatus.PENDING),
                )
                val preparationFinishedAt = SystemClock.elapsedRealtimeNanos()
                // Persist the SMS first so a Python error cannot lose the capture.
                val saveStartedAt = SystemClock.elapsedRealtimeNanos()
                val stored = store.append(message, generation)
                val initialSaveMs = elapsedMs(saveStartedAt)
                if (stored && store.canHandOff(id, generation)) {
                    if (hasSmsPermission(context)) {
                        val reception = SmsReceptionTiming(
                            enteredAtNanos = enteredAtNanos,
                            receiverSetupMs = elapsedMs(enteredAtNanos, queuedAtNanos),
                            executorWaitMs = elapsedMs(queuedAtNanos, workerStartedAt),
                            messagePreparationMs = elapsedMs(workerStartedAt, preparationFinishedAt),
                            initialSaveMs = initialSaveMs,
                            // Includes delivery/scheduling and cross-clock error.
                            smsTimestampToReceiverMs = receivedAt - sentAt,
                        )
                        val result = PythonBridge.acknowledge(context, message, reception)
                        val applied = store.completeHandoff(id, generation, result)
                        Log.i(TAG, "Python handoff: status=${result.status}, elapsedMs=${result.elapsedMillis}, applied=$applied")
                        result.performance?.let {
                            Log.i("SmsPerformance", "sms_id=$id timings=${it.toJson()}")
                        }
                        // Enqueue a separate job only after the short local handoff.
                        // Cloud calls must never extend the broadcast execution window.
                        if (applied) SmsClassificationWorker.enqueue(context, id, generation)
                    } else {
                        store.setEnabled(false)
                    }
                }
                Log.i(TAG, "SMS event handled: stored=$stored, elapsedMs=${elapsedMs(enteredAtNanos)}")
            } catch (_: Exception) {
                // Neither sender nor body (including exception text) belongs in logcat.
                Log.e(TAG, "SMS capture failed; no message content logged.")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun hasSmsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "FinanceSmsCapture"
        private val executor = Executors.newSingleThreadExecutor()
    }
}
