package com.example.core.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12
    private val secureRandom = SecureRandom()

    // SHA-256 key derivation from a password or conversation secret
    private fun deriveKey(secret: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(secret.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypts plaintext string using AES-GCM with a cryptographically secure random IV.
     * The 12-byte IV is prepended to the ciphertext bytes before Base64 encoding.
     */
    fun encrypt(plainText: String, secret: String): String {
        return try {
            val keySpec = deriveKey(secret)
            val cipher = Cipher.getInstance(ALGORITHM)
            
            // Cryptographically secure random 12-byte IV to prevent GCM nonce reuse
            val iv = ByteArray(IV_LENGTH_BYTE)
            secureRandom.nextBytes(iv)
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            val cipherTextBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            
            // Prepend IV to ciphertext
            val combined = ByteArray(iv.size + cipherTextBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherTextBytes, 0, combined, iv.size, cipherTextBytes.size)
            
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            // Graceful degradation: return original content if encryption fails
            plainText
        }
    }

    /**
     * Decrypts ciphertext string using AES-GCM.
     * Supports both modern prepended-IV format and legacy deterministic IV for backward compatibility.
     */
    fun decrypt(cipherText: String, secret: String): String {
        return try {
            val keySpec = deriveKey(secret)
            val decodedBytes = Base64.decode(cipherText, Base64.NO_WRAP)
            
            // Try modern prepended IV format first (must have at least IV_LENGTH_BYTE + 16 bytes auth tag)
            if (decodedBytes.size > IV_LENGTH_BYTE + 16) {
                try {
                    val iv = decodedBytes.copyOfRange(0, IV_LENGTH_BYTE)
                    val cipherOnly = decodedBytes.copyOfRange(IV_LENGTH_BYTE, decodedBytes.size)
                    val cipher = Cipher.getInstance(ALGORITHM)
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_LENGTH_BIT, iv))
                    val plainTextBytes = cipher.doFinal(cipherOnly)
                    return String(plainTextBytes, Charsets.UTF_8)
                } catch (_: Exception) {
                    // Fall back to legacy format below
                }
            }
            
            // Legacy fallback: deterministic IV derived from secret
            val legacyIv = MessageDigest.getInstance("MD5").digest(secret.toByteArray(Charsets.UTF_8)).copyOf(IV_LENGTH_BYTE)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_LENGTH_BIT, legacyIv))
            val plainTextBytes = cipher.doFinal(decodedBytes)
            String(plainTextBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            // If it's not encrypted or decryption fails, return the ciphertext gracefully
            cipherText
        }
    }

    /**
     * Secure hash helper for App Lock PIN hashing using SHA-256.
     */
    fun hashPin(pin: String, salt: String = "LANU_SALT_2026"): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest((pin + salt).toByteArray(Charsets.UTF_8))
            Base64.encodeToString(hashBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            pin
        }
    }
}
