package com.abijith.expensetracker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts user-entered credentials, not an API key embedded in the APK.
 * Keystore holds the encryption key; only authenticated ciphertext goes on disk.
 * No biometric prompt is required: opted-in background work must run while locked
 * after the first device unlock. This is not protection against a compromised app.
 */
internal class CloudCredentialVault(context: Context,
    private val alias: String = "finance-tracker-cloud-v1") {
    private val file = AtomicFile(File(context.noBackupFilesDir, "cloud_credentials.enc"))
    private val associatedData = "${context.packageName}:$alias:v1".toByteArray(Charsets.UTF_8)

    fun load(): CloudCredentials? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val encoded = file.openRead().use { input ->
            val buffer = ByteArray(MAX_BYTES + 1)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            buffer.copyOf(count)
        }
        require(encoded.size <= MAX_BYTES)
        val envelope = JSONObject(String(encoded, Charsets.UTF_8))
        require(envelope.getInt("version") == 1)
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        require(iv.size == 12)
        val encrypted = Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)
        val key = keyStore().getKey(alias, null) as? SecretKey
            ?: error("Saved credentials unavailable") // Never create a replacement on read.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData)
        val payload = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        require(payload.getBoolean("upload_consent"))
        return CloudCredentials(payload.getString("classification_key"), payload.getString("category_key"),
            payload.getString("model"), payload.getString("session"))
    }

    fun save(credentials: CloudCredentials) {
        val payload = JSONObject().put("classification_key", credentials.classificationApiKey)
            .put("category_key", credentials.categoryApiKey).put("model", credentials.model)
            .put("session", credentials.session).put("upload_consent", true)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey()) // Provider generates a fresh IV.
        cipher.updateAAD(associatedData)
        val encrypted = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        val envelope = JSONObject().put("version", 1)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        var output: FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    fun clear() {
        file.delete()
        check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() &&
            !File(file.baseFile.path + ".new").exists())
        // Deleting ciphertext is sufficient to forget credentials. An orphaned
        // encryption key contains no API key; cleanup must not undo a successful clear.
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    companion object { private const val MAX_BYTES = 32768 }
}
