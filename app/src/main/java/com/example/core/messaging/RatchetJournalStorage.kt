package com.example.core.messaging

import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** Only ciphertext is accepted by this file layer; no Room or user file deletion. */
internal interface RatchetJournalStorage {
    fun read(): String?
    fun write(ciphertext: String)
}

internal class RatchetFileJournal(noBackupRoot: File, ownerId: String) : RatchetJournalStorage {
    private val root = noBackupRoot.canonicalFile
    private val directory = File(root, "lanu-ratchet-v1")
    private val file: AtomicFile
    init {
        require(ownerId.matches(Regex("[a-f0-9]{64}")))
        check((directory.isDirectory || directory.mkdirs()) && directory.absoluteFile == directory.canonicalFile && directory.canonicalFile.parentFile == root)
        val fd = Os.open(root.absolutePath, OsConstants.O_RDONLY, 0)
        try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
        file = AtomicFile(File(directory, "$ownerId.journal"))
    }
    private fun validate() {
        check(directory.absoluteFile == directory.canonicalFile && directory.canonicalFile.parentFile == root)
        for (suffix in listOf("", ".bak", ".new")) {
            val candidate = File(file.baseFile.absolutePath + suffix)
            check(candidate.absoluteFile == candidate.canonicalFile && (!candidate.exists() || candidate.isFile))
        }
    }
    override fun read(): String? {
        validate()
        if (!file.baseFile.exists() && !File(file.baseFile.absolutePath + ".bak").exists()) return null
        return file.openRead().use { stream ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            try {
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count == 0 || bytes.size().toLong() + count > 8L * 1024 * 1024) throw IOException("Güvenli oturum kaydı okunamadı.")
                    bytes.write(buffer, 0, count)
                }
                bytes.toString(Charsets.US_ASCII.name())
            } finally { buffer.fill(0) }
        }
    }
    override fun write(ciphertext: String) {
        require(ciphertext.length in 1..8 * 1024 * 1024 && ciphertext.all { it.code in 32..126 })
        validate()
        val stream = file.startWrite()
        var finished = false
        try {
            stream.write(ciphertext.toByteArray(Charsets.US_ASCII))
            stream.fd.sync()
            file.finishWrite(stream)
            finished = true
            check(read() == ciphertext)
            // AtomicFile alone does not establish parent-directory durability.
            val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
        } catch (error: Exception) {
            if (!finished) file.failWrite(stream)
            throw IOException("Güvenli oturum kalıcı kaydedilemedi.")
        }
    }
}

internal interface RatchetBox {
    fun seal(role: String, plain: String): String
    fun open(role: String, cipher: String): String
}
