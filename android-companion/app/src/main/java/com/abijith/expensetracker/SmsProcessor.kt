package com.abijith.expensetracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/** Shared, short local path for real reception and debug simulation. Call off the UI thread. */
internal class SmsProcessor(
    private val store: SmsStore,
    private val hasPermission: () -> Boolean,
    private val acknowledge: (CapturedSms, SmsReceptionTiming?) -> PythonHandoff,
    private val enqueueCloud: (String, Long) -> Unit,
) {
    /** Save first; pause/clear tokens prevent late results from restoring old cards. */
    fun process(message: CapturedSms, generation: Long, reception: SmsReceptionTiming? = null): Boolean {
        if (!hasPermission()) return false
        val saveStartedAt = SystemClock.elapsedRealtimeNanos()
        if (!store.append(message, generation)) return false
        val timing = reception?.copy(initialSaveMs = elapsedMs(saveStartedAt))
        if (!store.canHandOff(message.id, generation)) return true
        if (!hasPermission()) {
            store.setEnabled(false)
            return true
        }
        val result = try {
            acknowledge(message, timing)
        } catch (_: Exception) {
            // Keep the saved copy; never log exception text containing private input.
            PythonHandoff(HandoffStatus.FAILED)
        }
        val applied = store.completeHandoff(message.id, generation, result)
        Log.i("FinanceSmsCapture", "Python handoff: status=${result.status}, elapsedMs=${result.elapsedMillis}, applied=$applied")
        result.performance?.let {
            Log.i("SmsPerformance", "sms_id=${message.id} simulated=${message.simulated} timings=${it.toJson()}")
        }
        // No HTTP in this path. The same separate WorkManager job handles both sources.
        if (applied && hasPermission()) enqueueCloud(message.id, generation)
        return true
    }

    companion object {
        fun forContext(context: Context, store: SmsStore = SmsStore.get(context),
                       allowCloud: Boolean = true): SmsProcessor = SmsProcessor(
            store,
            hasPermission = {
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
                    PackageManager.PERMISSION_GRANTED
            },
            acknowledge = { message, timing -> PythonBridge.acknowledge(context, message, timing) },
            enqueueCloud = { id, generation ->
                if (allowCloud) SmsClassificationWorker.enqueue(context, id, generation)
            },
        )
    }
}
