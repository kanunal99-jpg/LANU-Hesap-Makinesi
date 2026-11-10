package com.example.core.update

import java.io.IOException
import java.io.InputStream

/**
 * Reject non-progressing and malformed OTA streams before byte counts reach
 * the existing checksum, copy or APK signature validation.
 */
internal object OtaStreamReadPolicy {
    fun read(input: InputStream, buffer: ByteArray): Int {
        require(buffer.isNotEmpty()) { "OTA okuma tamponu boş olamaz." }
        val count = input.read(buffer)
        if (count == 0) throw IOException("OTA okuma akışı ilerlemiyor.")
        if (count < -1 || count > buffer.size)
            throw IOException("OTA okuma akışı geçersiz uzunluk bildirdi.")
        return count
    }
}
