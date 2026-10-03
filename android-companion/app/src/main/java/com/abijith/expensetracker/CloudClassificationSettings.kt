package com.abijith.expensetracker

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class CloudClassificationState(val enabled: Boolean = false, val model: String = DEFAULT_CLASSIFIER_MODEL,
    val remembered: Boolean = false, val loading: Boolean = false, val error: String? = null)
const val DEFAULT_CLASSIFIER_MODEL = "gpt-5.6-luna"

// Deliberately not a data class: its toString must never reveal the keys.
class CloudCredentials(val classificationApiKey: String, val categoryApiKey: String,
    val model: String, val session: String)

/** Credential lifecycle independent of the UI. Tests use their own instance and vault. */
internal class CloudSettings(private var vault: CloudCredentialVault? = null, loading: Boolean = false) {
    private var credentials: CloudCredentials? = null
    private val mutableState = MutableStateFlow(CloudClassificationState(loading = loading))
    val state: StateFlow<CloudClassificationState> = mutableState.asStateFlow()

    init { if (vault != null) restore(vault!!) }

    @Synchronized
    fun restore(storage: CloudCredentialVault) {
        vault = storage
        try {
            val saved = storage.load()
            require(saved == null || (valid(saved.classificationApiKey, saved.categoryApiKey, saved.model) &&
                runCatching { UUID.fromString(saved.session).toString() == saved.session }.getOrDefault(false)))
            credentials = saved
            mutableState.value = CloudClassificationState(saved != null, saved?.model ?: DEFAULT_CLASSIFIER_MODEL,
                remembered = saved != null)
        } catch (_: Exception) {
            credentials = null
            mutableState.value = CloudClassificationState(error =
                "Saved credentials could not be restored. Cloud is off. Forget saved keys, then enter them again.")
        }
    }

    @Synchronized
    fun enable(classificationApiKey: String, categoryApiKey: String, model: String, consent: Boolean,
        remember: Boolean = false): Boolean {
        val first = classificationApiKey.trim()
        val second = categoryApiKey.trim()
        val modelId = model.trim()
        if (!consent || !valid(first, second, modelId)) return false
        val next = CloudCredentials(first, second, modelId, UUID.randomUUID().toString())
        try {
            if (remember) {
                checkNotNull(vault).save(next)
            } else vault?.clear() // Memory-only enable must not restore older saved consent later.
        } catch (_: Exception) {
            mutableState.value = state.value.copy(error = "Could not save or remove credentials. No new settings were enabled.")
            return false
        }
        credentials = next
        mutableState.value = CloudClassificationState(true, modelId, remembered = remember)
        return true
    }

    /** Disabling also forgets persisted keys/consent and invalidates queued sessions. */
    @Synchronized
    fun disable(): Boolean {
        credentials = null
        return try {
            vault?.clear()
            mutableState.value = CloudClassificationState(model = state.value.model)
            true
        } catch (_: Exception) {
            mutableState.value = CloudClassificationState(model = state.value.model, error =
                "Cloud is off for now, but saved keys could not be removed. Retry Forget saved keys before restarting.")
            false
        }
    }

    @Synchronized fun session(): String? = credentials?.session
    @Synchronized fun credentialsFor(session: String): CloudCredentials? = credentials?.takeIf { it.session == session }

    private fun valid(first: String, second: String, model: String): Boolean =
        validKey(first) && validKey(second) && first != second && model.isNotBlank() && model.length <= 100
    private fun validKey(key: String): Boolean = key.isNotBlank() && key.length <= 4096 && key.none { it.isWhitespace() }
}

/** Restored without opening an Activity. Initialization is called on background threads. */
object CloudClassificationSettings {
    private val settings = CloudSettings(loading = true)
    private var initialized = false
    val state: StateFlow<CloudClassificationState> = settings.state

    @Synchronized
    fun initialize(context: Context) {
        if (!initialized) {
            settings.restore(CloudCredentialVault(context.applicationContext))
            initialized = true
        }
    }

    fun enable(classificationApiKey: String, categoryApiKey: String, model: String, consent: Boolean,
        remember: Boolean = false): Boolean = settings.enable(classificationApiKey, categoryApiKey, model, consent, remember)
    fun disable(): Boolean = settings.disable()
    fun session(): String? = settings.session()
    fun credentialsFor(session: String): CloudCredentials? = settings.credentialsFor(session)
}
