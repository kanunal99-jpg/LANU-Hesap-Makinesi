package com.example.core.messaging

import org.json.JSONObject
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable, password-encrypted recovery; a public contact code is never a recovery secret. */
object IdentityBackup {
    private const val ITERATIONS = 210000
    private val aad = "LANU-identity-backup-v1".toByteArray(Charsets.UTF_8)
    internal fun softwarePbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        var block = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val result = block.copyOf()
        repeat(iterations - 1) {
            block = mac.doFinal(block)
            for (i in result.indices) result[i] = (result[i].toInt() xor block[i].toInt()).toByte()
        }
        return result
    }
    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val bytes = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        catch (_: java.security.NoSuchAlgorithmException) { softwarePbkdf2(password, salt, ITERATIONS) }
        finally { spec.clearPassword() }
        return SecretKeySpec(bytes, "AES")
    }
    fun export(identity: MessagingIdentity, password: String): String {
        require(password.length in 12..256) { "Yedek parolası en az 12 karakter olmalıdır." }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)); cipher.updateAAD(aad)
        val value = JSONObject().put("id", identity.id).put("code", identity.code).put("nonce", identity.nonce)
            .put("publicKey", identity.publicKey).put("privateKey", identity.privateKey).toString()
        return JSONObject().put("version", 1).put("kdf", "PBKDF2-HMAC-SHA256").put("iterations", ITERATIONS)
            .put("salt", UserCodes.encode(salt)).put("iv", UserCodes.encode(iv))
            .put("data", UserCodes.encode(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))).toString()
    }
    fun restore(backup: String, password: String): MessagingIdentity {
        require(backup.length <= 32768 && password.length in 12..256) { "Yedek dosyasını ve parolanızı kontrol edin." }
        return try {
            val envelope = JSONObject(backup)
            require(envelope.getInt("version") == 1 && envelope.getString("kdf") == "PBKDF2-HMAC-SHA256" && envelope.getInt("iterations") == ITERATIONS)
            val salt = UserCodes.decode(envelope.getString("salt")); val iv = UserCodes.decode(envelope.getString("iv"))
            require(salt.size == 16 && iv.size == 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)); cipher.updateAAD(aad)
            val value = JSONObject(String(cipher.doFinal(UserCodes.decode(envelope.getString("data"))), Charsets.UTF_8))
            val identity = MessagingIdentity(value.getString("id"), value.getString("code"), value.getString("nonce"), value.getString("publicKey"), value.getString("privateKey"))
            require(identity.id == UserCodes.id(identity.publicKey) && UserCodes.matches(identity.code, identity.publicKey, identity.nonce))
            val challenge = "LANU-key-pair-check"
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(UserCodes.decode(identity.publicKey)))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey); verifier.update(challenge.toByteArray())
            require(verifier.verify(UserCodes.decode(UserCodes.sign(identity, challenge))))
            identity
        } catch (_: Exception) { throw IllegalArgumentException("Kimlik yedeği açılamadı. Dosyayı ve parolanızı kontrol edin.") }
    }
}
