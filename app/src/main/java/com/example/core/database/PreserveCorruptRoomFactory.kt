package com.example.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * Production Room v4 open-helper guard against AndroidX's default destructive
 * corruption recovery. SQLite's default SupportSQLiteOpenHelper.Callback
 * deletes database files (including WAL/shm) on corruption.
 *
 * This wrapper preserves the original Room-generated schema/identity/migration
 * callbacks and **only** replaces onCorruption. It never copies, renames,
 * deletes, rebuilds or logs a private database, and never enables SQLCipher.
 *
 * A corrupt file remains unavailable until explicitly reviewed/restored. This
 * intentionally favors possible temporary unavailability over silent loss of
 * PIN, message history, unsent ciphertext and identity-linked settings.
 */
internal class PreserveCorruptRoomFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory =
        FrameworkSQLiteOpenHelperFactory()
) : SupportSQLiteOpenHelper.Factory {

    override fun create(
        configuration: SupportSQLiteOpenHelper.Configuration
    ): SupportSQLiteOpenHelper {
        val original = configuration.callback
        val preserve = object : SupportSQLiteOpenHelper.Callback(original.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) =
                original.onConfigure(db)

            override fun onCreate(db: SupportSQLiteDatabase) =
                original.onCreate(db)

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                original.onUpgrade(db, oldVersion, newVersion)

            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                original.onDowngrade(db, oldVersion, newVersion)

            override fun onOpen(db: SupportSQLiteDatabase) =
                original.onOpen(db)

            override fun onCorruption(db: SupportSQLiteDatabase) {
                // NEVER invoke original.onCorruption(): its default is to
                // delete the database, sidecars and attached databases.
                // No exception, file path, SQL or user record enters logs.
                // Close the damaged handle so subsequent operations fail
                // instead of writing into an untrusted connection.
                try {
                    db.close()
                } catch (_: Exception) {
                    // Incomplete SQLite handles may be uncloseable. Leave every
                    // file untouched for an explicitly consented recovery.
                }
            }
        }
        return delegate.create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name)
                .callback(preserve)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                // Never let the FrameworkSQLiteOpenHelper recreate an
                // unopenable database, even when the caller requested it.
                .allowDataLossOnRecovery(false)
                .build()
        )
    }
}
