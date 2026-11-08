package com.example.core.database

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.core.messaging.RatchetFileJournal
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Explicit same-device stage keys; no missing-key regeneration and no plaintext fallback. */
internal object DatabaseCipherKeyVault {
    private fun checkName(name:String) { require(Regex("lanu-backup-probe-[a-f0-9]{32}\\.db").matches(name)) }
    private fun alias(name:String)="lanu_sqlcipher_stage_v1_$name"
    private fun store()=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun aad(name:String)="LANU-SQLCIPHER-STAGE-v1|$name".toByteArray(Charsets.US_ASCII)
    private fun journal(context:Context,name:String):RatchetFileJournal {
        checkName(name)
        val id=MessageDigest.getInstance("SHA-256").digest(aad(name)).joinToString("") { "%02x".format(it) }
        return RatchetFileJournal(context.noBackupFilesDir,id)
    }
    @Synchronized fun create(context:Context,name:String):ByteArray {
        checkName(name)
        val keyStore=store(); val file=journal(context,name)
        check(file.read()==null && !keyStore.containsAlias(alias(name))) { "Mevcut veritabanı anahtarı değiştirilmedi." }
        val wrapping=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias(name),KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
        val material=ByteArray(32).also(SecureRandom()::nextBytes)
        try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,wrapping);cipher.updateAAD(aad(name))
            file.write("sck1:"+Base64.encodeToString(cipher.iv+cipher.doFinal(material),Base64.NO_WRAP))
            val readBack=read(context,name)
            try { check(MessageDigest.isEqual(material,readBack)) } finally { readBack.fill(0) }
            return material.copyOf()
        } finally { material.fill(0) }
    }
    @Synchronized fun read(context:Context,name:String):ByteArray {
        checkName(name)
        try {
            val saved=checkNotNull(journal(context,name).read())
            require(saved.startsWith("sck1:") && saved.length<=160)
            val bytes=Base64.decode(saved.substring(5),Base64.NO_WRAP)
            try {
                require(bytes.size==60)
                val key=store().getKey(alias(name),null) as? SecretKey ?: error("missing key")
                val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(0,12)))
                cipher.updateAAD(aad(name))
                return cipher.doFinal(bytes,12,bytes.size-12).also { check(it.size==32) }
            } finally { bytes.fill(0) }
        } catch (_:Exception) { throw SecurityException("Şifreli veritabanı anahtarı açılamadı; kayıtlar korundu.") }
    }
}
