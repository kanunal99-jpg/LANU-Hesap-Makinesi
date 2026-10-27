package com.example.core.update

import java.io.IOException
import java.io.InputStream

/**
 * A nonempty OTA buffer must make progress or reach EOF. Some broken streams
 * can return zero forever; silently retrying them would hang checksum
 * validation, resumed cache hashing, or the APK transfer itself.
 *
 * Does not modify any bytes, permission, trust policy or downloaded file.
 */
internal object OtaStreamReadPolicy {
    fun read(input: InputStream, buffer: ByteArray): Int {
        require(buffer.isNotEmpty()) { "OTA okuma tamponu boş olamaz." }
        val count = input.read(buffer)
        if (count == 0) throw IOException("OTA okuma akışı ilerlemiyor.")
        return count
    }
}
