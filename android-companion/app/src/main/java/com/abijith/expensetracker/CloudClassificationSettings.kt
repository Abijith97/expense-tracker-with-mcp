package com.abijith.expensetracker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class CloudClassificationState(val enabled: Boolean = false, val model: String = DEFAULT_CLASSIFIER_MODEL)
const val DEFAULT_CLASSIFIER_MODEL = "gpt-5.6-luna"

// Deliberately not a data class: its toString must never reveal the key.
class CloudCredentials(val apiKey: String, val model: String, val session: String)

/** Personal POC: key and consent live only in this app process, never on disk.
 * Closing the screen is fine; a process restart requires entering the key again.
 * A random session prevents work from a previous process being authorized later.
 */
object CloudClassificationSettings {
    private var credentials: CloudCredentials? = null
    private val mutableState = MutableStateFlow(CloudClassificationState())
    val state: StateFlow<CloudClassificationState> = mutableState.asStateFlow()

    @Synchronized
    fun enable(apiKey: String, model: String, consent: Boolean): Boolean {
        val key = apiKey.trim()
        val modelId = model.trim()
        if (!consent || key.isBlank() || key.any { it.isWhitespace() } ||
            modelId.isBlank() || modelId.length > 100) return false
        credentials = CloudCredentials(key, modelId, UUID.randomUUID().toString())
        mutableState.value = CloudClassificationState(true, modelId)
        return true
    }

    @Synchronized
    fun disable() {
        credentials = null
        mutableState.value = state.value.copy(enabled = false)
    }

    @Synchronized
    fun session(): String? = credentials?.session

    @Synchronized
    fun credentialsFor(session: String): CloudCredentials? = credentials?.takeIf { it.session == session }
}
