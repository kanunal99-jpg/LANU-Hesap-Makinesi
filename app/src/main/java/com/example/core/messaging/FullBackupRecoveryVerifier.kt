package com.example.core.messaging

import android.content.Context
import android.util.Base64
import androidx.room.Room
import com.example.core.database.AppDatabase
import com.example.core.database.PreserveCorruptRoomFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.DataInputStream
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/** Explicit password-verified recovery rehearsal. No live DB/identity/PIN writer is called. */
internal object FullBackupRecoveryVerifier {
    data class Result(val summary: FullDataBackup.Summary, val tablesVerified: Int = 7)
    private val mutex = Mutex()

    suspend fun verify(context: Context, archive: ByteArray, password: String): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            withTimeout(120_000L) {
                val opened = FullDataBackup.open(archive, password)
                currentCoroutineContext().ensureActive()
                val directory = File(context.cacheDir, "backup-verification")
                check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                check(directory.isDirectory || directory.mkdirs())
                // Only on a new explicit verification. Stale files are encrypted
                // with process-local keys which cannot survive process death.
                directory.listFiles()?.filter { BackupRecoveryFilePolicy.isProbeOrSidecar(it.name) }
                    ?.forEach { check(it.isFile && it.delete()) { "Önceki doğrulama dosyası temizlenemedi." } }
                check(directory.usableSpace >= BackupRecoveryFilePolicy.minimumFreeBytes(archive.size.toLong())) {
                    "Yedeği değiştirmeden sınamak için yeterli boş alan yok."
                }
                val file = File(directory, "lanu-backup-probe-${UUID.randomUUID().toString().replace("-", "")}.db")
                requireProbePath(context, file)
                check(file.createNewFile()) { "Doğrulama dosyası güvenle oluşturulamadı." }
                val key = ByteArray(32)
                var database: AppDatabase? = null
                var verified = false
                try {
                    SecureRandom().nextBytes(key)
                    System.loadLibrary("sqlcipher")
                    database = open(context, file, key)
                    val sql = database.openHelper.writableDatabase
                    val owner = currentCoroutineContext()
                    sql.beginTransaction()
                    try {
                        FullDataBackup.populateRecoveryProbe(sql, opened) { owner.ensureActive() }
                        FullDataBackup.verifyRecoveryProbe(sql, opened) { owner.ensureActive() }
                        sql.setTransactionSuccessful()
                    } finally { sql.endTransaction() }
                    // This is the disposable encrypted probe's WAL, never the live DB.
                    sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
                        check(it.moveToFirst() && it.getInt(0) == 0) { "Doğrulama yazımları tamamlanamadı." }
                    }
                    database.close(); database = null
                    owner.ensureActive()
                    val header = ByteArray(16)
                    DataInputStream(file.inputStream()).use { it.readFully(header) }
                    check(!header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) {
                        "Geçici veritabanının şifrelenmesi doğrulanamadı."
                    }
                    database = open(context, file, key)
                    val reopened = database.openHelper.writableDatabase
                    reopened.beginTransaction()
                    try {
                        FullDataBackup.verifyRecoveryProbe(reopened, opened) { owner.ensureActive() }
                        reopened.setTransactionSuccessful()
                    } finally { reopened.endTransaction() }
                    verified = true
                    Result(opened.summary)
                } finally {
                    try { database?.close() }
                    finally {
                        key.fill(0)
                        val cleaned = cleanProbe(context, file)
                        if (verified) check(cleaned) { "Geçici doğrulama dosyaları temizlenemedi." }
                    }
                }
            }
        }
    }

    internal fun requireProbePath(context: Context, file: File) {
        val directory = File(context.cacheDir, "backup-verification").canonicalFile
        require(directory.parentFile == context.cacheDir.canonicalFile &&
            file.canonicalFile.parentFile == directory && BackupRecoveryFilePolicy.isProbe(file.name)) {
            "Canlı veritabanı veya yedek dosyası doğrulama hedefi olamaz."
        }
    }

    private fun open(context: Context, file: File, key: ByteArray): AppDatabase {
        requireProbePath(context, file)
        val passphrase = Base64.encode(key, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
        return Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, file.absolutePath)
            .openHelperFactory(PreserveCorruptRoomFactory(SupportOpenHelperFactory(passphrase, null, true)))
            .build()
    }

    private fun cleanProbe(context: Context, file: File): Boolean {
        requireProbePath(context, file)
        return listOf("", "-wal", "-shm", "-journal").map { suffix ->
            val candidate = File(file.absolutePath + suffix)
            !candidate.exists() || (candidate.isFile && candidate.delete())
        }.all { it }
    }
}
