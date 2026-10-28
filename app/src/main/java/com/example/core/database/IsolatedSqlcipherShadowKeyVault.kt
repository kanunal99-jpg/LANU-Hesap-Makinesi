package com.example.core.database

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Synthetic encrypted-shadow test key vault. No production DB key is created,
 * loaded, rotated or reset here. This file MUST NOT be wired to live startup.
 *
 * 256-bit random SQLCipher secret is AES-GCM wrapped with AndroidKeyStore; only
 * ciphertext is persisted in noBackupFilesDir. Missing/corrupt wrap while the
 * corresponding shadow exists is an unrecoverable test error, NEVER a reason
 * to silently mint a replacement key or discard the encrypted database.
 *
 * AndroidKeyStore does not guarantee a hardware-backed key on every emulator
 * or device. Do not claim StrongBox/hardware assurance from this API alone.
 */
internal object IsolatedSqlcipherShadowKeyVault {
    private val idPattern = Regex("[A-Za-z0-9_-]{1,48}")
    private const val WRAPPED_SIZE = 12 + 32 + 16

    private fun location(context: Context, id: String): File {
        require(idPattern.matches(id)) { "Invalid synthetic shadow ID" }
        return File(context.noBackupFilesDir, "lanu_shadow_test_$id.wrapped")
    }

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun alias(id: String) = "lanu-shadow-test-$id"

    private fun key(id: String, allowCreate: Boolean): SecretKey {
        val storage = store()
        val existing = storage.getKey(alias(id), null) as? SecretKey
        if (existing != null) return existing
        check(allowCreate) { "Protected SQLCipher shadow key is missing; no fallback" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias(id),
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized fun unlockOrCreate(context: Context, id: String, shadowExists: Boolean): ByteArray {
        val file = location(context, id)
        if (file.exists()) {
            require(file.isFile && file.length() == WRAPPED_SIZE.toLong()) {
                "Wrapped SQLCipher shadow key has invalid size; no reset"
            }
            try {
                val sealed = file.readBytes()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(id, false),
                    GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
                cipher.updateAAD(id.toByteArray(Charsets.US_ASCII))
                val plain = cipher.doFinal(sealed.copyOfRange(12, sealed.size))
                check(plain.size == 32) { "Invalid decrypted shadow passphrase" }
                return plain
            } catch (_: Exception) {
                throw IllegalStateException("SQLCipher shadow key cannot be unlocked; no reset")
            }
        }
        check(!shadowExists) {
            "Encrypted shadow exists without its wrapped key; refusing data loss"
        }
        val plain = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(id, true))
        cipher.updateAAD(id.toByteArray(Charsets.US_ASCII))
        val sealed = cipher.iv + cipher.doFinal(plain)
        check(sealed.size == WRAPPED_SIZE) { "Unexpected wrapped key format" }
        val temp = File.createTempFile("lanu_shadow_test_", ".tmp", context.noBackupFilesDir)
        try {
            FileOutputStream(temp).use { output ->
                output.write(sealed)
                output.fd.sync()
            }
            check(!file.exists() && temp.renameTo(file)) {
                "Unable to atomically store the wrapped shadow key"
            }
        } catch (e: Exception) {
            temp.delete()
            plain.fill(0)
            throw e
        }
        return plain
    }

    /** Test-only cleanup of an ID-validated isolated key; never called by the app. */
    @Synchronized fun discardIsolatedFixture(context: Context, id: String) {
        val file = location(context, id)
        check(file.delete() || !file.exists()) { "Unable to clean isolated test wrap" }
        store().deleteEntry(alias(id))
    }
}
