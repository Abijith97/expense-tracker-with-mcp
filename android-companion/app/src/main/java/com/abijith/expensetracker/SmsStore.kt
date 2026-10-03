package com.abijith.expensetracker

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** A diagnostic copy, not a record in Android's SMS inbox or the finance database. */
data class CapturedSms(
    val id: String,
    val sender: String,
    val body: String,
    val sentAtMillis: Long,
    val receivedAtMillis: Long,
    val pythonHandoff: PythonHandoff? = null,
    val classification: SmsClassification? = null,
)

data class SmsCaptureState(
    val enabled: Boolean = false,
    val generation: Long = 0,
    val messages: List<CapturedSms> = emptyList(),
    val error: String? = null,
)

/**
 * Phone-only diagnostic history shared by the screen and receiver.
 * AtomicFile avoids half-written JSON; noBackupFilesDir excludes these copies
 * from Android backup/transfer. No SMS content is written to logs.
 * The UI pages cards, but storage never evicts a message at a display boundary.
 * This whole-file JSON store is a small POC, not a scalable expense database.
 */
class SmsStore internal constructor(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "sms_capture.json"))
    private val mutableState = MutableStateFlow(readFromDisk())
    val state: StateFlow<SmsCaptureState> = mutableState.asStateFlow()

    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        val current = state.value
        if (enabled && current.error != null) return false
        if (current.enabled == enabled) return true
        return save(current.copy(
            enabled = enabled,
            generation = current.generation + 1,
            messages = if (enabled) current.messages else current.messages.map { message ->
                // An already-running Python call cannot be killed safely. Pausing
                // discards its eventual result and leaves no misleading Pending card.
                val interrupted = if (message.pythonHandoff?.status == HandoffStatus.PENDING) {
                    message.copy(pythonHandoff = PythonHandoff(HandoffStatus.INTERRUPTED))
                } else message
                interruptClassification(interrupted)
            },
        ))
    }

    /** Capture a token before queuing work. Pause/clear invalidate old tokens. */
    @Synchronized
    fun activeGeneration(): Long? = state.value.takeIf { it.enabled }?.generation

    @Synchronized
    fun append(message: CapturedSms, generation: Long): Boolean {
        val current = state.value
        if (!current.enabled || current.generation != generation) return false
        if (current.messages.any { it.id == message.id }) return false
        return save(current.copy(messages = listOf(message) + current.messages))
    }

    @Synchronized
    fun canHandOff(messageId: String, generation: Long): Boolean =
        state.value.enabled && state.value.generation == generation &&
            state.value.messages.any {
                it.id == messageId && it.pythonHandoff?.status == HandoffStatus.PENDING
            }

    /** Update the existing SMS, without resurrecting a cleared or paused entry. */
    @Synchronized
    fun completeHandoff(messageId: String, generation: Long, result: PythonHandoff): Boolean {
        if (!canHandOff(messageId, generation)) return false
        return save(state.value.copy(messages = state.value.messages.map { message ->
            if (message.id == messageId) message.copy(pythonHandoff = result) else message
        }))
    }

    /** An explicit call schedules a new attempt; pending work is not duplicated. */
    @Synchronized
    fun prepareClassification(id: String, generation: Long, model: String): SmsClassification? {
        val current = state.value
        val message = current.messages.find { it.id == id } ?: return null
        if (!current.enabled || current.generation != generation ||
            message.classification?.status in setOf(ClassificationStatus.WAITING, ClassificationStatus.RUNNING)) return null
        val pending = SmsClassification(ClassificationStatus.WAITING, UUID.randomUUID().toString(), model)
        return if (save(current.copy(messages = current.messages.map {
            if (it.id == id) it.copy(classification = pending) else it
        }))) pending else null
    }

    @Synchronized
    fun startClassification(id: String, generation: Long, attempt: String): CapturedSms? {
        val current = state.value
        val message = current.messages.find { it.id == id } ?: return null
        if (!current.enabled || current.generation != generation ||
            message.classification?.attemptId != attempt ||
            message.classification.status != ClassificationStatus.WAITING) return null
        val started = message.copy(classification = message.classification.copy(status = ClassificationStatus.RUNNING))
        return if (save(current.copy(messages = current.messages.map { if (it.id == id) started else it }))) started else null
    }

    /** Check before the second API call; pause or clear must stop it. */
    @Synchronized
    fun canContinueClassification(id: String, generation: Long, attempt: String): Boolean {
        val current = state.value
        val message = current.messages.find { it.id == id } ?: return false
        return current.enabled && current.generation == generation &&
            message.classification?.attemptId == attempt &&
            message.classification.status == ClassificationStatus.RUNNING
    }

    @Synchronized
    fun finishClassification(id: String, generation: Long, result: SmsClassification): Boolean {
        val current = state.value
        val message = current.messages.find { it.id == id } ?: return false
        if (!current.enabled || current.generation != generation ||
            message.classification?.attemptId != result.attemptId ||
            message.classification.status !in setOf(ClassificationStatus.WAITING, ClassificationStatus.RUNNING)) return false
        return save(current.copy(messages = current.messages.map {
            if (it.id == id) it.copy(classification = result) else it
        }))
    }

    @Synchronized
    fun interruptClassifications(): Boolean = save(state.value.copy(
        messages = state.value.messages.map(::interruptClassification),
    ))

    private fun interruptClassification(message: CapturedSms): CapturedSms =
        if (message.classification?.status in setOf(ClassificationStatus.WAITING, ClassificationStatus.RUNNING)) {
            message.copy(classification = message.classification?.copy(status = ClassificationStatus.INTERRUPTED))
        } else message

    @Synchronized
    fun clear(): Boolean = save(
        state.value.copy(
            messages = emptyList(),
            generation = state.value.generation + 1,
            error = null,
        ),
    )

    private fun readFromDisk(): SmsCaptureState {
        return try {
            if (!file.baseFile.exists()) return SmsCaptureState()
            val json = file.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            require(json.getInt("schemaVersion") == 1)
            val messages = json.getJSONArray("messages")
            SmsCaptureState(
                enabled = json.getBoolean("enabled"),
                generation = json.getLong("generation"),
                messages = (0 until messages.length()).map { index ->
                    val item = messages.getJSONObject(index)
                    CapturedSms(
                        id = item.getString("id"),
                        sender = item.getString("sender"),
                        body = item.getString("body"),
                        sentAtMillis = item.getLong("sentAtMillis"),
                        receivedAtMillis = item.getLong("receivedAtMillis"),
                        classification = item.optJSONObject("classification")?.let(SmsClassification::fromJson),
                        pythonHandoff = item.optJSONObject("python_handoff")?.let { handoff ->
                            val status = HandoffStatus.valueOf(handoff.getString("status"))
                            if (status == HandoffStatus.PENDING) {
                                // Loading from disk means the process that began this
                                // call is gone. Never present that as success or retry it silently.
                                PythonHandoff(HandoffStatus.INTERRUPTED)
                            } else {
                                PythonHandoff(
                                    status = status,
                                    acknowledgement = if (handoff.has("acknowledgement"))
                                        handoff.getString("acknowledgement") else null,
                                    elapsedMillis = if (handoff.has("elapsed_millis"))
                                        handoff.getLong("elapsed_millis") else null,
                                    performance = handoff.optJSONObject("performance")?.let {
                                        SmsPerformance.fromJson(it)
                                    },
                                )
                            }
                        },
                    )
                },
            )
        } catch (_: Exception) {
            // Do not expose a JSON exception: it may contain message text.
            SmsCaptureState(error = "Could not read local history. Clear captured messages to reset it.")
        }
    }

    private fun save(next: SmsCaptureState): Boolean {
        var output: FileOutputStream? = null
        return try {
            val messages = JSONArray()
            next.messages.forEach { message ->
                val item = JSONObject()
                        .put("id", message.id)
                        .put("sender", message.sender)
                        .put("body", message.body)
                        .put("sentAtMillis", message.sentAtMillis)
                        .put("receivedAtMillis", message.receivedAtMillis)
                message.classification?.let { item.put("classification", it.toJson()) }
                message.pythonHandoff?.let { handoff ->
                    val result = JSONObject().put("status", handoff.status.name)
                    handoff.acknowledgement?.let { result.put("acknowledgement", it) }
                    handoff.elapsedMillis?.let { result.put("elapsed_millis", it) }
                    handoff.performance?.let { result.put("performance", it.toJson()) }
                    item.put("python_handoff", result)
                }
                messages.put(item)
            }
            val json = JSONObject()
                .put("schemaVersion", 1)
                .put("enabled", next.enabled)
                .put("generation", next.generation)
                .put("messages", messages)
            output = file.startWrite()
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            mutableState.value = next.copy(error = null)
            true
        } catch (_: Exception) {
            file.failWrite(output)
            mutableState.value = state.value.copy(
                enabled = false,
                generation = state.value.generation + 1,
                messages = state.value.messages.map { message ->
                    val interrupted = if (message.pythonHandoff?.status == HandoffStatus.PENDING) {
                        message.copy(pythonHandoff = PythonHandoff(HandoffStatus.INTERRUPTED))
                    } else message
                    interruptClassification(interrupted)
                },
                error = "Could not save local capture state. Revoke SMS permission in app settings " +
                    "to stop capture across restarts, then clear history and try again.",
            )
            false
        }
    }

    companion object {
        const val PAGE_SIZE = 20
        @Volatile private var instance: SmsStore? = null

        fun get(context: Context): SmsStore = instance ?: synchronized(this) {
            instance ?: SmsStore(context.applicationContext).also { instance = it }
        }
    }
}
