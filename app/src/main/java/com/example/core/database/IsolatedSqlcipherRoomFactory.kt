package com.example.core.database

import android.content.Context
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * REAL SQLCipher Room open-helper, restricted to test-only shadow files.
 *
 * This class deliberately has NO call site in AppDatabase.getDatabase().
 * It neither reads nor replaces lanu_calculator_db, nor checkpoints its WAL,
 * changes Room v4 schema, moves files, or generates a lost passphrase.
 * Physical-device/live cutover requires separately validated migration gates.
 */
internal object IsolatedSqlcipherRoomFactory {
    private val name = Regex("""lanu_sqlcipher_shadow_test_[A-Za-z0-9_-]{1,48}\.db""")

    fun open(context: Context, database: File, passphrase: ByteArray): AppDatabase {
        require(passphrase.size == 32) { "SQLCipher test passphrase must have 256-bit entropy" }
        val expectedDirectory = context.applicationContext.getDatabasePath(
            "lanu_calculator_db"
        ).canonicalFile.parentFile
        require(database.canonicalFile.parentFile == expectedDirectory &&
            name.matches(database.name) && !database.isDirectory) {
            "Only isolated SQLCipher test shadow filenames are permitted"
        }
        System.loadLibrary("sqlcipher")
        // The copy protects the caller's secret; no key ever leaves the process
        // as a log, preference, Android backup, or repository artifact.
        val factory = SupportOpenHelperFactory(passphrase.copyOf(), null, true)
        return Room.databaseBuilder(
            context.applicationContext, AppDatabase::class.java, database.absolutePath
        ).openHelperFactory(PreserveCorruptRoomFactory(factory))
            // A wrong key or unexpected schema must fail closed, never recreate.
            .build()
    }
}
