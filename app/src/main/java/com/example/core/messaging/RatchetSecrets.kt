package com.example.core.messaging

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
import javax.crypto.spec.SecretKeySpec

/** Device-only keys. Missing material is an error, never an implicit session reset. */
internal class RatchetSecrets private constructor(
    private val owner: String,
    private val material: ByteArray
) : RatchetBox, AutoCloseable {
    private var closed = false
    @Synchronized fun <T> withPickleKey(block: (ByteArray) -> T): T {
        check(!closed)
        val key = material.copyOfRange(0,32)
        return try { block(key) } finally { key.fill(0) }
    }
    @Synchronized override fun seal(role: String, plain: String): String {
        check(!closed)
        require(role in ROLES && plain.length in 1..6 * 1024 * 1024)
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val rawKey = material.copyOfRange(32,64)
        return try {
            require(bytes.size <= 6 * 1024 * 1024)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(rawKey,"AES"))
            cipher.updateAAD(aad(owner,role))
            "drb1:" + Base64.encodeToString(cipher.iv + cipher.doFinal(bytes),Base64.NO_WRAP)
        } finally { bytes.fill(0); rawKey.fill(0) }
    }
    @Synchronized override fun open(role: String, cipher: String): String {
        check(!closed)
        require(role in ROLES && cipher.startsWith("drb1:") && cipher.length in 45..8 * 1024 * 1024)
        val bytes=Base64.decode(cipher.substring(5),Base64.NO_WRAP)
        val rawKey=material.copyOfRange(32,64)
        return try {
            require(bytes.size in 29..(6 * 1024 * 1024 + 28))
            val aes=Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.DECRYPT_MODE,SecretKeySpec(rawKey,"AES"),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            aes.updateAAD(aad(owner,role))
            val plain=aes.doFinal(bytes,12,bytes.size-12)
            try { String(plain,Charsets.UTF_8) } finally { plain.fill(0) }
        } catch (_: Exception) { throw IllegalStateException("Güvenli oturum anahtarı veya kaydı doğrulanamadı.") }
        finally { bytes.fill(0); rawKey.fill(0) }
    }
    @Synchronized override fun close() { material.fill(0); closed=true }
    companion object {
        private val ROLES=setOf("state","journal")
        private fun aad(owner:String,role:String)="LANU-RATCHET-KEYS-v1|$owner|$role".toByteArray(Charsets.US_ASCII)
        /** create=true is reserved for an explicit activation with NO previous state. */
        @Synchronized fun open(context:Context,owner:String,create:Boolean=false):RatchetSecrets {
            require(owner.matches(Regex("[a-f0-9]{64}")))
            val journal=RatchetFileJournal(context.noBackupFilesDir,owner)
            // Separate owner namespace from state journal; both stay in noBackup.
            val keyOwner=java.security.MessageDigest.getInstance("SHA-256")
                .digest("LANU-KEY-WRAP|$owner".toByteArray(Charsets.US_ASCII))
                .joinToString("") { "%02x".format(it) }
            val storage=RatchetFileJournal(context.noBackupFilesDir,keyOwner)
            val saved=storage.read()
            val alias="lanu_ratchet_wrap_v1_$owner"
            val keyStore=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val exists=keyStore.containsAlias(alias)
            if(saved == null) {
                check(create && !exists && journal.read() == null) { "Güvenli oturum anahtarı eksik; yeni anahtar üretilmedi." }
                val key=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
                    init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true).build()); generateKey()
                }
                val material=ByteArray(64).also(SecureRandom()::nextBytes)
                try {
                    val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE,key); cipher.updateAAD(aad(owner,"root"))
                    storage.write("drk1:"+Base64.encodeToString(cipher.iv+cipher.doFinal(material),Base64.NO_WRAP))
                    return RatchetSecrets(owner,material.copyOf())
                } finally { material.fill(0) }
            }
            check(exists && saved.startsWith("drk1:") && saved.length <= 256) { "Cihaz anahtarı eksik; kayıtlar korundu." }
            val bytes=Base64.decode(saved.substring(5),Base64.NO_WRAP)
            return try {
                require(bytes.size == 92)
                val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE,keyStore.getKey(alias,null) as SecretKey,GCMParameterSpec(128,bytes.copyOfRange(0,12)))
                cipher.updateAAD(aad(owner,"root"))
                RatchetSecrets(owner,cipher.doFinal(bytes,12,bytes.size-12))
            } catch (_: Exception) { throw IllegalStateException("Cihaz anahtarı açılamadı; mevcut kayıtlar korundu.") }
            finally { bytes.fill(0) }
        }
    }
}
