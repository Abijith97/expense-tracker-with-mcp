package com.abijith.expensetracker

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Real Android Keystore, isolated files/alias, synthetic credentials, no HTTP. */
@RunWith(AndroidJUnit4::class)
class CloudPersistenceInstrumentedTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(target.cacheDir, "cloud-vault-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(target) {
        override fun getNoBackupFilesDir(): File = directory
    }
    private val alias = "finance-test-${UUID.randomUUID()}"
    private val vault = CloudCredentialVault(context, alias)
    private val first = "DEMO-CLASSIFICATION-PRIVATE"
    private val second = "DEMO-CATEGORY-PRIVATE"

    @After
    fun cleanOnlyThisTestsCiphertextAndKeystoreAlias() {
        vault.clear()
        directory.delete()
    }

    private fun enabled(remember: Boolean = true): CloudSettings = CloudSettings(vault).also {
        assertTrue(it.enable(first, second, DEFAULT_CLASSIFIER_MODEL, true, remember))
    }

    @Test
    fun ciphertextRestoresKeysConsentModelAndTheSameQueuedSessionWithoutAnActivity() {
        val initial = enabled()
        val session = initial.session()!!
        val disk = File(directory, "cloud_credentials.enc").readText()
        assertFalse(disk.contains(first))
        assertFalse(disk.contains(second))
        assertFalse(disk.contains(session))
        val restored = CloudSettings(CloudCredentialVault(context, alias))
        assertTrue(restored.state.value.enabled)
        assertTrue(restored.state.value.remembered)
        assertEquals(DEFAULT_CLASSIFIER_MODEL, restored.state.value.model)
        assertEquals(session, restored.session())
        assertEquals(first, restored.credentialsFor(session)!!.classificationApiKey)
        assertEquals(second, restored.credentialsFor(session)!!.categoryApiKey)
        assertNull(restored.credentialsFor("old-unrelated-session"))
        assertFalse(restored.state.value.toString().contains(first))
        assertFalse(restored.state.value.toString().contains(second))
    }

    @Test
    fun memoryOnlyModeDoesNotRestoreConsentOrLeaveOlderRememberedKeys() {
        val settings = enabled()
        val oldSession = settings.session()!!
        assertTrue(settings.enable(first, second, DEFAULT_CLASSIFIER_MODEL, true, remember = false))
        assertTrue(settings.state.value.enabled)
        assertFalse(settings.state.value.remembered)
        assertNull(settings.credentialsFor(oldSession))
        assertFalse(File(directory, "cloud_credentials.enc").exists())
        val restarted = CloudSettings(vault)
        assertFalse(restarted.state.value.enabled)
        assertNull(restarted.session())
    }

    @Test
    fun disablingForgetsSavedConsentAndRotationInvalidatesOldJobs() {
        val settings = enabled()
        val oldSession = settings.session()!!
        assertTrue(settings.enable("DEMO-NEW-FIRST", "DEMO-NEW-SECOND", DEFAULT_CLASSIFIER_MODEL, true, true))
        assertNull(settings.credentialsFor(oldSession))
        val restarted = CloudSettings(vault)
        assertNull(restarted.credentialsFor(oldSession))
        assertEquals(settings.session(), restarted.session())
        assertTrue(restarted.disable())
        assertNull(restarted.session())
        assertFalse(CloudSettings(vault).state.value.enabled)
        assertFalse(File(directory, "cloud_credentials.enc").exists())
    }

    @Test
    fun corruptCiphertextFailsClosedWithSafeErrorAndExplicitForgetRecovers() {
        enabled()
        val file = File(directory, "cloud_credentials.enc")
        val envelope = org.json.JSONObject(file.readText())
        val ciphertext = android.util.Base64.decode(envelope.getString("ciphertext"), android.util.Base64.NO_WRAP)
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        envelope.put("ciphertext", android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP))
        file.writeText(envelope.toString())
        val settings = CloudSettings(vault)
        assertFalse(settings.state.value.enabled)
        assertNull(settings.session())
        assertNotNull(settings.state.value.error)
        assertFalse(settings.state.value.error!!.contains(first))
        assertTrue(settings.disable())
        assertNull(settings.state.value.error)
        assertTrue(settings.enable(first, second, DEFAULT_CLASSIFIER_MODEL, true, true))
    }

    @Test
    fun missingEncryptionKeyNeverGeneratesAReplacementOrAuthorizesUploads() {
        enabled()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        val restored = CloudSettings(vault)
        assertFalse(restored.state.value.enabled)
        assertNull(restored.session())
        assertNotNull(restored.state.value.error)
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    }

    @Test
    fun rememberingRequiresValidKeysAndExplicitConsent() {
        val settings = CloudSettings(vault)
        assertFalse(settings.enable(first, second, DEFAULT_CLASSIFIER_MODEL, false, true))
        assertFalse(settings.enable(first, first, DEFAULT_CLASSIFIER_MODEL, true, true))
        assertNull(vault.load())
        assertFalse(settings.state.value.enabled)
    }
}
