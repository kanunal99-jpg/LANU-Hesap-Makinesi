package com.example.core.media

import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * Bounded, non-consuming first-line file-signature check for Android PhotoPicker
 * and SAF provider streams. This is NOT a parser, malware scanner or decoder.
 *
 * Encrypted files must still be validated by the appropriate media decoder
 * before the user opens them. For photos, EXIF/location metadata must be removed
 * by re-encoding pixels before passing to the large-media encryption engine.
 */
object PrivateMediaSourcePreflight {
    private const val MAX_SNIFF_BYTES = 20
    private val pngHeader = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    )
    private val pdfHeader = "%PDF-".toByteArray(Charsets.US_ASCII)

    /**
     * The caller MUST close the returned stream; it owns and closes source.
     * No file or plaintext is persisted by this operation.
     *
     * type: MIME reported by ContentResolver; no trust in filenames/extensions.
     * images: must be pixel-reencoded before this function, or caller must set
     * sanitizedImage false (rejected) rather than leak GPS/EXIF to a recipient.
     * video: original MP4 may include device/location metadata. It can only be
     * staged after an explicit UI acknowledgement; not automatic send.
     */
    fun inspect(
        source: InputStream,
        mime: String,
        sanitizedImage: Boolean = false,
        userApprovedOriginalVideo: Boolean = false
    ): InputStream {
        // We own the source immediately, even when MIME or user consent fails.
        // Otherwise rejected SAF/PhotoPicker streams can leak file descriptors.
        val stream = PushbackInputStream(source, MAX_SNIFF_BYTES)
        try {
        require(mime in setOf("image/jpeg", "image/png", "application/pdf", "video/mp4")) {
            "Büyük dosya türü desteklenmiyor."
        }
        require(mime !in setOf("image/jpeg", "image/png") || sanitizedImage) {
            "Fotoğrafın konum metaverisi temizlenmeden aktarılamaz."
        }
        require(mime != "video/mp4" || userApprovedOriginalVideo) {
            "Videodaki olası konum/metaveriler için kullanıcı onayı gerekiyor."
        }
        val needed = when (mime) {
            "image/jpeg" -> 3
            "image/png" -> pngHeader.size
            "application/pdf" -> 8
            "video/mp4" -> 16
            else -> error("Unsupported input")
        }
            val prefix = ByteArray(needed)
            var read = 0
            var stalled = 0
            while (read < needed) {
                val n = stream.read(prefix, read, needed - read)
                if (n < 0) throw IllegalArgumentException("Dosyanın başlangıcı eksik.")
                if (n == 0) {
                    if (++stalled >= 3) throw IOException("Dosya sağlayıcısı veri göndermedi.")
                    continue
                }
                read += n
            }
            val valid = when (mime) {
                "image/jpeg" -> prefix[0] == 0xff.toByte() &&
                    prefix[1] == 0xd8.toByte() && prefix[2] == 0xff.toByte()
                "image/png" -> prefix.copyOfRange(0, 8).contentEquals(pngHeader)
                "application/pdf" -> prefix.copyOfRange(0, 5).contentEquals(pdfHeader) &&
                    (prefix[5].toInt().toChar() in '0'..'9') && prefix[6] == '.'.code.toByte() &&
                    (prefix[7].toInt().toChar() in '0'..'9')
                "video/mp4" -> {
                    // ISO BMFF: 32-bit box size, the ASCII 'ftyp' marker, and
                    // printable four-byte brand. Full MP4 box parsing comes later.
                    val declaredBox = ((prefix[0].toLong() and 255) shl 24) or
                        ((prefix[1].toLong() and 255) shl 16) or
                        ((prefix[2].toLong() and 255) shl 8) or
                        (prefix[3].toLong() and 255)
                    declaredBox in 16..4096 &&
                        prefix.copyOfRange(4, 8).contentEquals(byteArrayOf(0x66, 0x74, 0x79, 0x70)) &&
                        prefix.copyOfRange(8, 12).all { it.toInt() in 32..126 }
                }
                else -> false
            }
            require(valid) { "Dosya içeriği seçilen türe uymuyor." }
            stream.unread(prefix)
            return stream
        } catch (error: Throwable) {
            stream.close()
            throw error
        }
    }
}
