package com.example.core.media

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Storage-independent, authenticated chunked media encryption prototype.
 *
 * SECURITY BOUNDARY: this does NOT upload/send attachments. Never put key bytes
 * or plaintext paths in relay, Room, logs, or object-storage metadata. Later,
 * the receiver's verified E2EE peer channel must authenticate the manifest and
 * transport the one-time key. A private authorized object store is still needed.
 *
 * Incoming content is only released to a randomly named PRIVATE temp file
 * after *all* GCM tags and the full ciphertext SHA-256 have been verified.
 */
object PrivateMediaCrypto {
    const val CHUNK_BYTES = 64 * 1024
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    private const val KEY_BYTES = 32
    private const val FILE_ID_BYTES = 16
    private const val NONCE_PREFIX_BYTES = 8
    private const val GCM_TAG_BYTES = 16
    private val allowedMime = setOf(
        "image/jpeg", "image/png", "video/mp4",
        "application/pdf", "text/plain",
        PrivateOfficeDocumentPreflight.DOCX_MIME,
        PrivateOfficeDocumentPreflight.XLSX_MIME,
        PrivateOfficeDocumentPreflight.PPTX_MIME
    )
    private val hexChars = Regex("[0-9a-f]+")
    private val sha = Regex("[0-9a-f]{64}")

    /**
     * The manifest has no secret key. It must be authenticated INSIDE an E2EE
     * peer message before a remote receiver trusts it.
     */
    data class Manifest(
        val version: Int,
        val fileId: String,
        val mime: String,
        val plainBytes: Long,
        val chunks: Int,
        val noncePrefix: String,
        val cipherSha256: String
    )

    /**
     * The file contains only ciphertext. keyMaterial is secret and stays in
     * process memory; destroy it after the future verified E2EE key exchange.
     * Never persist this result as-is or serialize the key to a server.
     */
    class EncryptedFile(
        val ciphertextFile: File,
        val manifest: Manifest,
        val keyMaterial: ByteArray
    ) {
        fun destroyKey() { keyMaterial.fill(0) }
    }

    /**
     * Destination MUST be context.cacheDir or another private app directory.
     * Files are always randomly named, and incomplete output is deleted.
     *
     * The upload feature intentionally remains disabled until an authorized,
     * private, quota-controlled storage layer and E2EE key exchange are built.
     */
    fun encryptToPrivateFile(
        source: InputStream,
        declaredBytes: Long,
        mime: String,
        privateDirectory: File,
        random: SecureRandom = SecureRandom()
    ): EncryptedFile = encryptPrepared(source, declaredBytes, mime, privateDirectory, random, false)

    /**
     * OOXML input must be a size-bounded APP-PRIVATE staged file, never an
     * unchecked remote/provider stream. No Office document upload is enabled.
     */
    fun encryptOfficeDocumentToPrivateFile(
        stagedPrivateFile: File,
        mime: String,
        privateDirectory: File,
        random: SecureRandom = SecureRandom()
    ): EncryptedFile {
        PrivateOfficeDocumentPreflight.validate(stagedPrivateFile, mime)
        // Validate the live private Storage reservation budget before spending
        // CPU and disk space encrypting a file the relay must reject.
        PrivateMediaTransferBudget.encryptedBytes(stagedPrivateFile.length())
        return FileInputStream(stagedPrivateFile).use {
            encryptPrepared(it, stagedPrivateFile.length(), mime, privateDirectory, random, true)
        }
    }

    private fun encryptPrepared(
        source: InputStream,
        declaredBytes: Long,
        mime: String,
        privateDirectory: File,
        random: SecureRandom,
        validatedOffice: Boolean
    ): EncryptedFile {
        require(validatedOffice || !PrivateOfficeDocumentPreflight.isOfficeMime(mime)) {
            "Office dosyaları yalnız doğrulanmış özel dosya alanından şifrelenebilir."
        }
        val count = validCount(declaredBytes, mime)
        require(privateDirectory.isDirectory) { "Özel dosya alanı bulunamadı." }
        val fileId = ByteArray(FILE_ID_BYTES).also(random::nextBytes)
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        val target = File.createTempFile("lanu-media-v1-", ".cipher", privateDirectory)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(target).use { fileOutput ->
                var consumed = 0L
                for (index in 0 until count) {
                    val expected = minOf(CHUNK_BYTES.toLong(), declaredBytes - consumed).toInt()
                    val block = readExactly(source, expected)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        Cipher.ENCRYPT_MODE,
                        SecretKeySpec(key, "AES"),
                        GCMParameterSpec(128, nonce(noncePrefix, index))
                    )
                    cipher.updateAAD(aad(fileId, mime, declaredBytes, count, index, expected))
                    val encrypted = cipher.doFinal(block)
                    check(encrypted.size == expected + GCM_TAG_BYTES)
                    fileOutput.write(encrypted)
                    digest.update(encrypted)
                    Arrays.fill(block, 0)
                    consumed += expected
                }
                require(source.read() == -1) { "Dosya bildirilen boyuttan daha büyük." }
                fileOutput.fd.sync()
            }
            val manifest = Manifest(
                version = 1,
                fileId = hex(fileId),
                mime = mime,
                plainBytes = declaredBytes,
                chunks = count,
                noncePrefix = hex(noncePrefix),
                cipherSha256 = hex(digest.digest())
            )
            check(target.length() == declaredBytes + count * GCM_TAG_BYTES.toLong())
            return EncryptedFile(target, manifest, key)
        } catch (error: Throwable) {
            target.delete()
            Arrays.fill(key, 0)
            throw error
        }
    }

    /**
     * Decrypt only into an app-private randomly named temporary file. Every
     * chunk must authenticate AND the whole encrypted object hash must match
     * before the temp file can be returned to the caller.
     *
     * On failure partially decrypted bytes are deleted. Callers must delete
     * the returned temp file after user-authorized viewing or SAF export.
     */
    fun decryptToVerifiedPrivateFile(
        encrypted: File,
        manifest: Manifest,
        keyMaterial: ByteArray,
        privateDirectory: File
    ): File {
        validate(manifest)
        require(keyMaterial.size == KEY_BYTES) { "Dosya anahtarı geçersiz." }
        require(privateDirectory.isDirectory) { "Özel dosya alanı bulunamadı." }
        // The caller MUST provide context.cacheDir/filesDir or a verified
        // private child directory: a File argument alone cannot prove Android
        // app ownership. Reject direct symlinks (including those targeting a
        // different file in the SAME private directory) and path traversal.
        // No follow-symlink / race-free open guarantee is claimed here: before
        // enabling remote downloads the owner must bind a protected staging
        // directory and use a no-follow file-descriptor open on Android.
        val canonicalDirectory = privateDirectory.canonicalFile
        val canonicalEncrypted = encrypted.canonicalFile
        require(privateDirectory.absoluteFile == canonicalDirectory &&
            encrypted.absoluteFile == canonicalEncrypted &&
            canonicalEncrypted.parentFile == canonicalDirectory &&
            encrypted.isFile &&
            encrypted.length() ==
            manifest.plainBytes + manifest.chunks * GCM_TAG_BYTES.toLong()) {
            "Şifreli dosya özel alanda değil, sembolik bağlantı veya boyutu geçersiz."
        }
        // Verify the complete encrypted object BEFORE writing even a temporary
        // plaintext byte. This prevents a wrong SHA manifest or truncated
        // object from creating any private plaintext at all. GCM tags still
        // authenticate each block independently on the following pass.
        val fullDigest = MessageDigest.getInstance("SHA-256")
        FileInputStream(encrypted).use { source ->
            val buffer = ByteArray(CHUNK_BYTES)
            var total = 0L
            var stalledReads = 0
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    if (++stalledReads >= 3) throw IOException("Şifreli dosya okumada ilerleme yok.")
                    continue
                }
                stalledReads = 0
                total += count
                require(total <= manifest.plainBytes +
                    manifest.chunks * GCM_TAG_BYTES.toLong()) {
                    "Şifreli medya boyutu güvenli sınırı aşıyor."
                }
                fullDigest.update(buffer, 0, count)
            }
        }
        require(MessageDigest.isEqual(
            fullDigest.digest(), fromHex(manifest.cipherSha256)
        )) { "Şifreli dosya özeti eşleşmiyor." }

        val plain = File.createTempFile("lanu-verified-", ".private", privateDirectory)
        val fileId = fromHex(manifest.fileId)
        val prefix = fromHex(manifest.noncePrefix)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileInputStream(encrypted).use { source ->
                FileOutputStream(plain).use { sink ->
                    var processed = 0L
                    for (index in 0 until manifest.chunks) {
                        val size = minOf(CHUNK_BYTES.toLong(), manifest.plainBytes - processed).toInt()
                        val chunk = readExactly(source, size + GCM_TAG_BYTES)
                        digest.update(chunk)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(
                            Cipher.DECRYPT_MODE,
                            SecretKeySpec(keyMaterial, "AES"),
                            GCMParameterSpec(128, nonce(prefix, index))
                        )
                        cipher.updateAAD(aad(fileId, manifest.mime, manifest.plainBytes,
                            manifest.chunks, index, size))
                        val decrypted = cipher.doFinal(chunk)  // Throws if tag wrong.
                        check(decrypted.size == size)
                        sink.write(decrypted)
                        Arrays.fill(decrypted, 0)
                        processed += size
                    }
                    require(source.read() == -1) { "Şifreli dosyada fazla veri bulunuyor." }
                    sink.fd.sync()
                }
            }
            require(MessageDigest.isEqual(digest.digest(), fromHex(manifest.cipherSha256))) {
                "Şifreli dosya özeti eşleşmiyor."
            }
            check(plain.length() == manifest.plainBytes)
            return plain
        } catch (error: Throwable) {
            plain.delete()
            throw error
        }
    }

    fun validate(manifest: Manifest) {
        require(manifest.version == 1 && manifest.mime in allowedMime)
        require(manifest.fileId.length == FILE_ID_BYTES * 2 &&
            manifest.fileId.matches(hexChars))
        require(manifest.noncePrefix.length == NONCE_PREFIX_BYTES * 2 &&
            manifest.noncePrefix.matches(hexChars))
        require(manifest.cipherSha256.matches(sha))
        require(manifest.chunks == validCount(manifest.plainBytes, manifest.mime)) {
            "Medya parçalarının sayısı veya boyutu geçersiz."
        }
    }

    private fun validCount(size: Long, mime: String): Int {
        require(mime in allowedMime) { "Dosya türü desteklenmiyor." }
        require(size in 1..MAX_FILE_BYTES) { "Dosya boyutu 64 MB sınırını aşıyor." }
        return ((size - 1) / CHUNK_BYTES + 1).toInt()
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var pos = 0
        var stalled = 0
        while (pos < size) {
            val count = input.read(result, pos, size - pos)
            if (count < 0) throw IOException("Dosya eksik veya aktarım kesildi.")
            if (count == 0) {
                if (++stalled >= 3) throw IOException("Dosya sağlayıcısı yanıt vermedi.")
                continue
            }
            stalled = 0
            pos += count
        }
        return result
    }

    private fun nonce(prefix: ByteArray, index: Int): ByteArray {
        require(prefix.size == NONCE_PREFIX_BYTES && index >= 0)
        return prefix + byteArrayOf(
            (index ushr 24).toByte(),
            (index ushr 16).toByte(),
            (index ushr 8).toByte(),
            index.toByte()
        )
    }

    private fun aad(id: ByteArray, mime: String, size: Long, count: Int, index: Int,
        plainChunkBytes: Int): ByteArray {
        val buf = ByteArrayOutputStream(110)
        DataOutputStream(buf).use {
            it.writeInt(1)
            it.write(id)
            it.writeUTF(mime)
            it.writeLong(size)
            it.writeInt(count)
            it.writeInt(index)
            it.writeInt(plainChunkBytes)
        }
        return buf.toByteArray()
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun fromHex(text: String): ByteArray {
        require(text.length % 2 == 0 && text.matches(hexChars))
        return ByteArray(text.length / 2) { i ->
            text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
