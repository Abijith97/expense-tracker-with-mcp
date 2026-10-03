package com.abijith.expensetracker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class CloudClassificationState(val enabled: Boolean = false, val model: String = DEFAULT_CLASSIFIER_MODEL)
const val DEFAULT_CLASSIFIER_MODEL = "gpt-5.6-luna"

// Deliberately not a data class: its toString must never reveal the key.
class CloudCredentials(
    val classificationApiKey: String,
    val categoryApiKey: String,
    val model: String,
    val session: String,
)

/** Personal POC: both keys and consent live only in this app process, never on disk.
 * Closing the screen is fine; a process restart requires entering both keys again.
 * A random session prevents work from a previous process being authorized later.
 */
object CloudClassificationSettings {
    private var credentials: CloudCredentials? = null
    private val mutableState = MutableStateFlow(CloudClassificationState())
    val state: StateFlow<CloudClassificationState> = mutableState.asStateFlow()

    @Synchronized
    fun enable(classificationApiKey: String, categoryApiKey: String, model: String, consent: Boolean): Boolean {
        val classificationKey = classificationApiKey.trim()
        val categoryKey = categoryApiKey.trim()
        val modelId = model.trim()
        // Require distinct credentials so provider-side key totals separate the stages.
        // Do not validate remotely here: enabling must not incur an API call.
        if (!consent || !validKey(classificationKey) || !validKey(categoryKey) ||
            classificationKey == categoryKey ||
            modelId.isBlank() || modelId.length > 100) return false
        credentials = CloudCredentials(classificationKey, categoryKey, modelId, UUID.randomUUID().toString())
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

    private fun validKey(key: String): Boolean = key.isNotBlank() && key.none { it.isWhitespace() }
}
