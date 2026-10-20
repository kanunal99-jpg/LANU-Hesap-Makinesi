package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.SettingsEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Safeguards against accidental fallbackToDestructiveMigration in future changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomDataPreservationTest {
    @Test fun `PIN settings and history survive a normal database reopening`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "lanu_room_reopen_test.db"
        context.deleteDatabase(name)
        try {
            val first = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            first.settingsDao().insertSetting(SettingsEntity("pin_hash", "legacy-migration-test-record"))
            first.settingsDao().insertSetting(SettingsEntity("lanu_peer_id_saved", "peer"))
            first.close()
            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals("legacy-migration-test-record", reopened.settingsDao().getSettingValue("pin_hash"))
            assertEquals("peer", reopened.settingsDao().getSettingValue("lanu_peer_id_saved"))
            reopened.close()
        } finally { context.deleteDatabase(name) }
    }

    @Test fun `unknown old schema fails closed without deleting its original data`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "lanu_unknown_schema_test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        try {
            val old = SQLiteDatabase.openOrCreateDatabase(path, null)
            old.execSQL("CREATE TABLE legacy_private_data (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
            old.execSQL("INSERT INTO legacy_private_data(key,value) VALUES ('message','KEEP_ME')")
            old.version = 3
            old.close()
            val current = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                try {
                    current.settingsDao().getSettingValue("pin_hash")
                    fail("An unknown schema must never be wiped and recreated silently")
                } catch (_: IllegalStateException) { }
            } finally { current.close() }
            val inspect = SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val cursor = inspect.rawQuery("SELECT value FROM legacy_private_data WHERE key='message'", null)
                cursor.use {
                    assertTrue(it.moveToFirst())
                    assertEquals("KEEP_ME", it.getString(0))
                }
            } finally { inspect.close() }
        } finally { context.deleteDatabase(name) }
    }
}
