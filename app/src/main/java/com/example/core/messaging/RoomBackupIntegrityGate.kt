package com.example.core.messaging

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit full-backup, read-only integrity gate for current Room v4 SQLite.
 *
 * Runs ONLY when a user exports an authenticated full backup, inside the
 * consistent Room transaction. Never checks, changes or checkpoints files on
 * startup. No private SQL diagnostics, paths, PIN, messages or keys leave RAM.
 *
 * A failed integrity check blocks creation of a falsely trustworthy archive
 * while preserving the existing database and any recovery options.
 */
internal object RoomBackupIntegrityGate {
    fun requireHealthy(db: SupportSQLiteDatabase) {
        try {
            db.query("PRAGMA quick_check(1)").use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok" &&
                    !cursor.moveToNext()) { "Unhealthy SQLite" }
            }
            // SQLite quick_check does not validate foreign keys. Room v4 has
            // no declared FKs today; this remains a safe future guard.
            db.query("PRAGMA foreign_key_check").use { cursor ->
                check(!cursor.moveToFirst()) { "Broken SQLite references" }
            }
        } catch (_: Exception) {
            // Do not leak SQLite error content or file paths to UI/logs.
            throw IllegalStateException(
                "Yerel veritabanı bütünlüğü doğrulanamadı. Mevcut kayıtlar korunuyor; güvenli yedek oluşturulmadı."
            )
        }
    }
}
