package com.abijith.expensetracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/** Hosted inference runs here, not inside the short-lived SMS receiver. */
class SmsClassificationWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val id = inputData.getString("message_id") ?: return Result.failure()
        val attempt = inputData.getString("attempt_id") ?: return Result.failure()
        val session = inputData.getString("cloud_session") ?: return Result.failure()
        val generation = inputData.getLong("capture_generation", -1)
        val store = SmsStore.get(applicationContext)
        if (isStopped || ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECEIVE_SMS) !=
            PackageManager.PERMISSION_GRANTED) {
            store.finishClassification(id, generation, SmsClassification(ClassificationStatus.INTERRUPTED, attempt))
            return Result.failure()
        }
        val credentials = CloudClassificationSettings.credentialsFor(session)
        if (credentials == null) {
            store.finishClassification(id, generation,
                SmsClassification(ClassificationStatus.FAILED, attempt, errorCode = "configuration"))
            return Result.failure()
        }
        val message = store.startClassification(id, generation, attempt) ?: return Result.success()
        // Recheck consent immediately before inference. Requests already sent cannot be unsent.
        if (isStopped || CloudClassificationSettings.credentialsFor(session) == null) {
            store.finishClassification(id, generation, SmsClassification(ClassificationStatus.INTERRUPTED, attempt))
            return Result.failure()
        }
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val classification = PythonBridge.classifySms(applicationContext, message, credentials, attempt)
        if (!isStopped && CloudClassificationSettings.credentialsFor(session) != null) {
            val applied = store.finishClassification(id, generation, classification)
            Log.i("SmsClassification", "status=${classification.status} elapsed_ms=${elapsedMs(startedAt)} applied=$applied")
        }
        // No API retries here: failures stay visible, rather than counting as 'Other'.
        return if (classification.status == ClassificationStatus.FAILED) Result.failure() else Result.success()
    }

    companion object {
        private const val TAG = "sms-cloud-classification"

        fun enqueue(context: Context, id: String, generation: Long): Boolean {
            val session = CloudClassificationSettings.session() ?: return false
            val settings = CloudClassificationSettings.state.value
            val store = SmsStore.get(context)
            val pending = store.prepareClassification(id, generation, settings.model) ?: return false
            return try {
                val request = OneTimeWorkRequestBuilder<SmsClassificationWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    // Only IDs/tokens go in WorkManager, never a body or credential.
                    .setInputData(workDataOf("message_id" to id, "attempt_id" to pending.attemptId,
                        "cloud_session" to session, "capture_generation" to generation))
                    .addTag(TAG).build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "sms-classify-${pending.attemptId}", ExistingWorkPolicy.KEEP, request)
                true
            } catch (_: Exception) {
                store.finishClassification(id, generation,
                    pending.copy(status = ClassificationStatus.FAILED, errorCode = "scheduling"))
                false
            }
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
            SmsStore.get(context).interruptClassifications()
        }
    }
}
