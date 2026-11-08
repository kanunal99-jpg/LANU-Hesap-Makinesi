package com.example.core.database

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.room.withTransaction
import com.example.core.messaging.FullDataBackup
import com.example.core.messaging.FullBackupRecoveryVerifier
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/**
 * Prepares a persistent, Keystore-reopenable exact copy AFTER verifying the
 * owner's chosen portable backup. This does NOT switch the active database.
 * The restart selector must separately prove writers stopped / WAL / rollback.
 */
internal object VerifiedSqlCipherStage {
    class Prepared internal constructor(val name:String,val tableDigest:String) {
        override fun toString()="SqlCipherPrepared(redacted)"
    }
    private val mutex=Mutex()
    suspend fun prepare(context:Context,source:AppDatabase,identity:com.example.core.messaging.MessagingIdentity,archive:ByteArray,password:String):Prepared=mutex.withLock {
        withContext(Dispatchers.IO) {
            val backup=FullDataBackup.open(archive,password)
            check(backup.identity==identity) { "Yedek mevcut kimliğe ait değil; kayıtlar korunuyor." }
            FullBackupRecoveryVerifier.verify(context,archive,password)
            currentCoroutineContext().ensureActive()
            // Preserve device-only ratchet state in the encrypted database copy,
            // while requiring an actual independently reopened portable backup.
            val snapshot=source.withTransaction {
                val sql=source.openHelper.writableDatabase
                val portable=FullDataBackup.capture(sql,backup.identity)
                check(fingerprint(portable)==fingerprint(backup.payload)) {
                    "Yedek güncel verilerle aynı değil; yeni yedek oluşturun."
                }
                FullDataBackup.captureDeviceSnapshot(sql,backup.identity)
            }
            val name="lanu-backup-probe-${UUID.randomUUID().toString().replace("-","")}.db"
            val file=path(context,name)
            check(!file.exists() && file.parentFile!!.usableSpace>=
                com.example.core.messaging.BackupRecoveryFilePolicy.minimumFreeBytes(archive.size.toLong())+16L*1024*1024)
            check(file.createNewFile())
            val key=DatabaseCipherKeyVault.create(context,name)
            var encrypted:AppDatabase?=null
            try {
                System.loadLibrary("sqlcipher")
                val target=open(context,name,key)
                encrypted=target
                val owner=currentCoroutineContext()
                target.withTransaction {
                    FullDataBackup.populateRecoveryProbe(target.openHelper.writableDatabase,snapshot) { owner.ensureActive() }
                    FullDataBackup.verifyRecoveryProbe(target.openHelper.writableDatabase,snapshot) { owner.ensureActive() }
                }
                target.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
                    check(it.moveToFirst() && it.getInt(0)==0)
                }
                target.close();encrypted=null
                FileInputStream(file).use {
                    val header=ByteArray(16);check(it.read(header)==16)
                    check(!header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))
                }
                val reopenedKey=DatabaseCipherKeyVault.read(context,name)
                val reopened=try { open(context,name,reopenedKey) } finally { reopenedKey.fill(0) }
                encrypted=reopened
                reopened.withTransaction {
                    FullDataBackup.verifyRecoveryProbe(reopened.openHelper.writableDatabase,snapshot) { owner.ensureActive() }
                }
                owner.ensureActive()
                Prepared(name,fingerprint(snapshot.payload))
            } finally { try { encrypted?.close() } finally { key.fill(0) } }
        }
    }
    internal fun fingerprint(payload:org.json.JSONObject):String {
        val tables=payload.getJSONObject("tables")
        val canonical=DatabaseShadowParity.TABLES.sorted().joinToString("\n") { it+":"+tables.getJSONArray(it).toString() }
        return com.example.core.messaging.RatchetTransactionStore.digest(canonical)
    }
    internal fun path(context:Context,name:String):File {
        require(Regex("lanu-backup-probe-[a-f0-9]{32}\\.db").matches(name))
        val root=context.getDatabasePath("lanu_calculator_db").canonicalFile.parentFile!!
        return File(root,name).also { require(it.absoluteFile==it.canonicalFile && it.parentFile==root) }
    }
    internal fun open(context:Context,name:String,key:ByteArray):AppDatabase {
        val file=path(context,name);require(file.isFile && key.size==32)
        System.loadLibrary("sqlcipher")
        val passphrase=Base64.encode(key,Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
        return Room.databaseBuilder(context.applicationContext,AppDatabase::class.java,file.absolutePath)
            .openHelperFactory(PreserveCorruptRoomFactory(SupportOpenHelperFactory(passphrase,null,true))).build()
    }
}
