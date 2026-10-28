package com.example.core.database

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed AES-GCM envelope for an ISOLATED SQLCipher acceptance key.
 *
 * The stable per-shadow 256-bit data key is random, never a PIN, E2EE key or
 * signing key. A missing, malformed or unreadable envelope is never repaired
 * by generating a replacement key: doing so would strand ciphertext.
 *
 * This class deliberately accepts ONLY test-shadow names. No production
 * automatic key creation, live Room migration, recovery or key rotation.
 */
internal object SqlCipherShadowKeyVault {
    private const val PREFERENCES = "lanu_sqlcipher_ci_shadow_envelopes"
    private const val PREFIX = "lanu_sqlcipher_ci_"
    private val random = SecureRandom()
    private val lock = Any()

    private fun requireIsolated(name: String) {
        require(SqlCipherShadowDatabase.isIsolatedName(name)) {
            "Canlı veritabanı anahtarı işlemi reddedildi."
        }
    }

    private fun alias(name: String): String = PREFIX + name
    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun secretKey(name: String): SecretKey =
        (keystore().getKey(alias(name), null) as? SecretKey)
            ?: throw SecurityException("Şifreli veri anahtarı bulunamadı; kayıtlar korunuyor.")

    /** Called on a fresh isolated fixture ONCE. Never overwrite a key. */
    fun create(context: Context, name: String): ByteArray = synchronized(lock) {
        requireIsolated(name)
        val prefs = preferences(context)
        check(!prefs.contains(name) && !keystore().containsAlias(alias(name))) {
            "Mevcut şifreli anahtarın üzerine yazılmadı."
        }
        val wrapKey = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder(alias(name),
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
        val dataKey = ByteArray(32).also(random::nextBytes)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrapKey)
            val iv = cipher.iv
            check(iv.size == 12) { "GCM IV geçersiz." }
            val wrapped = cipher.doFinal(dataKey)
            val value = "v1:" + Base64.encodeToString(
                iv + wrapped, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            check(prefs.edit().putString(name, value).commit() &&
                prefs.getString(name, null) == value) {
                "Anahtar zarfı kalıcı saklanamadı; gölge veritabanı açılmadı."
            }
            dataKey
        } catch (error: Exception) {
            dataKey.fill(0)
            throw SecurityException("Şifreli anahtar hazırlanamadı; mevcut veri korunuyor.")
        }
    }

    /** Read-only unwrap. Wrong key, missing Keystore or tampering fails closed. */
    fun read(context: Context, name: String): ByteArray = synchronized(lock) {
        requireIsolated(name)
        try {
            val stored = preferences(context).getString(name, null)
                ?: throw SecurityException("Şifreli anahtar zarfı bulunamadı.")
            require(stored.startsWith("v1:") && stored.length in 40..256)
            val envelope = Base64.decode(stored.removePrefix("v1:"),
                Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            require(envelope.size >= 12 + 16 + 32)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(name),
                GCMParameterSpec(128, envelope.copyOfRange(0, 12)))
            val secret = cipher.doFinal(envelope.copyOfRange(12, envelope.size))
            require(secret.size == 32)
            secret
        } catch (_: Exception) {
            // Deliberately no chained provider exception, cipher bytes or paths.
            throw SecurityException("Şifreli veritabanı anahtarına erişilemedi; mevcut kayıtlar korunuyor.")
        }
    }

    /** Only for disposable CI test fixtures, never for user databases. */
    fun deleteIsolatedFixture(context: Context, name: String) = synchronized(lock) {
        requireIsolated(name)
        preferences(context).edit().remove(name).commit()
        keystore().deleteEntry(alias(name))
    }
}
