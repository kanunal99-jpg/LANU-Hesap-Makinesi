package com.example.core.media

import java.io.File
import java.io.IOException

/**
 * Deletes ONLY this feature's abandoned process-local temporary media files.
 *
 * PrivateMediaCrypto currently keeps its per-file encryption keys in RAM and
 * does not support durable upload/resume. After process death its ciphertext
 * cache entries have no usable recipient key, and verified plaintext cache
 * must NEVER survive into a later app session.
 *
 * This is not a general cache cleaner. It must not touch message databases,
 * backups, OTA APKs, thumbnails, Android documents or unrelated cache files.
 */
object PrivateMediaCacheRecovery {
    private val verifiedPlaintext = Regex("^lanu-verified-[A-Za-z0-9_-]+\\.private$")
    private val incompleteCipher = Regex("^lanu-media-v1-[A-Za-z0-9_-]+\\.cipher$")
    private val stagedPlaintext = Regex("^lanu-staged-[A-Za-z0-9_-]+\\.private$")

    data class Result(val removed: Int, val unableToRemove: Int)

    fun purge(cacheDir: File): Result {
        // This method receives application Context.cacheDir only. Canonical
        // containment prevents deleting outside the intended private cache if
        // a symlink somehow appeared.
        if (!cacheDir.isDirectory) return Result(0, 0)
        val base = try { cacheDir.canonicalFile } catch (_: IOException) {
            return Result(0, 0)
        }
        if (cacheDir.absoluteFile != base) return Result(0, 1)
        var removed = 0
        var failures = 0
        fun purgeFiles(directory: File, allowStaging: Boolean) {
        for (candidate in directory.listFiles().orEmpty()) {
            if (!verifiedPlaintext.matches(candidate.name) &&
                !incompleteCipher.matches(candidate.name) &&
                !(allowStaging && stagedPlaintext.matches(candidate.name))) continue
            val safe = try {
                candidate.absoluteFile == candidate.canonicalFile &&
                    candidate.canonicalFile.parentFile == directory.canonicalFile && candidate.isFile
            } catch (_: IOException) { false }
            if (!safe) {
                failures++
                continue
            }
            if (candidate.delete()) removed++ else failures++
        }
        }
        purgeFiles(cacheDir, false)
        val large = File(cacheDir, "lanu-private-large-media")
        if (large.exists()) {
            val safe = try {
                large.isDirectory && large.absoluteFile == large.canonicalFile &&
                    large.canonicalFile.parentFile == base
            } catch (_: IOException) { false }
            if (safe) purgeFiles(large, true) else failures++
        }
        return Result(removed, failures)
    }
}
