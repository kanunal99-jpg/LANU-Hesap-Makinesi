package com.example

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.PreserveCorruptRoomFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Isolated copies only; the live lanu_calculator_db is never opened, repaired
 * or deleted by these tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RoomCorruptionRetentionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class CapturingFactory : SupportSQLiteOpenHelper.Factory {
        lateinit var configuration: SupportSQLiteOpenHelper.Configuration
        override fun create(
            configuration: SupportSQLiteOpenHelper.Configuration
        ): SupportSQLiteOpenHelper {
            this.configuration = configuration
            return FrameworkSQLiteOpenHelperFactory().create(configuration)
        }
    }

    private fun callback(events: MutableList<String> = mutableListOf()) =
        object : SupportSQLiteOpenHelper.Callback(4) {
            override fun onConfigure(db: SupportSQLiteDatabase) { events.add("configure") }
            override fun onCreate(db: SupportSQLiteDatabase) {
                events.add("create")
                db.execSQL("CREATE TABLE retention_fixture (id TEXT PRIMARY KEY, content TEXT NOT NULL)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                events.add("upgrade")
                throw IllegalStateException("Unverified migration must fail")
            }
            override fun onOpen(db: SupportSQLiteDatabase) { events.add("open") }
        }

    @Test fun legacyCallbackCannotDeleteStoredDataWhenCorruptionIsReported() {
        val name = "lanu_corruption_retention_test.db"
        context.deleteDatabase(name) // Isolated test fixture only, before creation.
        val events = mutableListOf<String>()
        val capture = CapturingFactory()
        val safeFactory = PreserveCorruptRoomFactory(capture)
        val source = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(callback(events))
            // Even an unsafe caller request must be forced off by our factory.
            .allowDataLossOnRecovery(true).build()
        val helper = safeFactory.create(source)
        try {
            assertEquals(name, capture.configuration.name)
            assertEquals(4, capture.configuration.callback.version)
            assertFalse(capture.configuration.allowDataLossOnRecovery)
            assertFalse(capture.configuration.useNoBackupDirectory)
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO retention_fixture VALUES ('unsent', 'lanu2:KEEP_CIPHERTEXT')")
            assertTrue(events.contains("create"))
            assertTrue(events.contains("open"))

            capture.configuration.callback.onCorruption(db)
            assertFalse("Damaged handle must be closed", db.isOpen)
            val raw = SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                raw.rawQuery("SELECT content FROM retention_fixture WHERE id='unsent'", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("lanu2:KEEP_CIPHERTEXT", it.getString(0))
                }
            } finally { raw.close() }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun preservesCallerConfigurationAndNeverInvokesDestructiveCallback() {
        val name = "lanu_corruption_delegate_test.db"
        context.deleteDatabase(name)
        val capture = CapturingFactory()
        var deletes = 0
        val original = object : SupportSQLiteOpenHelper.Callback(4) {
            override fun onCreate(db: SupportSQLiteDatabase) =
                db.execSQL("CREATE TABLE durable (secret TEXT NOT NULL)")
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                throw IllegalStateException("Unknown schema must fail")
            }
            override fun onCorruption(db: SupportSQLiteDatabase) { deletes++ }
        }
        val helper = PreserveCorruptRoomFactory(capture).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name).callback(original).build()
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO durable VALUES ('PIN_VERIFIER')")
            capture.configuration.callback.onCorruption(db)
            assertEquals(0, deletes)
            assertTrue(context.getDatabasePath(name).isFile)
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
