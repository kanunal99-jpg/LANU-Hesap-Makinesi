package com.example

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.SettingsEntity
import com.example.core.repository.SecurityRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Test preferences are injected only here; production uses Keystore-backed preferences. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureSupabaseCredentialMigrationTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var preferences: SharedPreferences

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        preferences = context.getSharedPreferences(
            "test-protected-supabase-" + System.nanoTime(), Context.MODE_PRIVATE)
    }

    @After fun teardown() {
        database.close()
        preferences.edit().clear().commit()
    }

    private fun repository(prefs: SharedPreferences = preferences): SecurityRepository =
        SecurityRepository(context, database.settingsDao(), preferencesForTesting = prefs)

    @Test fun `new credentials are stored only in protected preferences`() = runBlocking {
        repository().saveSupabaseCredentials("https://test.example", "sensitive-test-key")
        assertNull(database.settingsDao().getSettingValue("supabase_url"))
        assertNull(database.settingsDao().getSettingValue("supabase_key"))
        assertEquals("https://test.example" to "sensitive-test-key",
            repository().getSupabaseCredentials())
    }

    @Test fun `legacy credentials migrate before plaintext rows are removed`() = runBlocking {
        database.settingsDao().insertSetting(SettingsEntity("supabase_url", "https://legacy.example"))
        database.settingsDao().insertSetting(SettingsEntity("supabase_key", "legacy-secret"))
        val result = repository().getSupabaseCredentials()
        assertEquals("https://legacy.example" to "legacy-secret", result)
        assertEquals("legacy-secret", preferences.getString("supabase_key", null))
        assertNull(database.settingsDao().getSettingValue("supabase_key"))
        assertNull(database.settingsDao().getSettingValue("supabase_url"))
        assertEquals(result, repository().getSupabaseCredentials())
    }

    @Test fun `failed encrypted migration leaves original plaintext credentials recoverable`() = runBlocking {
        database.settingsDao().insertSetting(SettingsEntity("supabase_url", "https://legacy.example"))
        database.settingsDao().insertSetting(SettingsEntity("supabase_key", "must-stay-recoverable"))
        val failing = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val delegate = preferences.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
                    override fun commit(): Boolean = false
                }
            }
        }
        try {
            repository(failing).getSupabaseCredentials()
            fail("Failed protected write must stop migration")
        } catch (_: SecurityException) { }
        assertEquals("must-stay-recoverable", database.settingsDao().getSettingValue("supabase_key"))
        assertEquals("https://legacy.example", database.settingsDao().getSettingValue("supabase_url"))
    }

    @Test fun `failed encrypted save cannot write new secret to Room`() = runBlocking {
        val failing = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val delegate = preferences.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
                    override fun commit(): Boolean = false
                }
            }
        }
        try {
            repository(failing).saveSupabaseCredentials("https://server.example", "new-secret")
            fail("Failed write must throw")
        } catch (_: SecurityException) { }
        assertNull(database.settingsDao().getSettingValue("supabase_key"))
        assertNull(database.settingsDao().getSettingValue("supabase_url"))
    }

    @Test fun `protected key takes priority over stale plaintext copies`() = runBlocking {
        repository().saveSupabaseCredentials("https://protected.example", "protected-secret")
        database.settingsDao().insertSetting(SettingsEntity("supabase_url", "https://outdated.example"))
        database.settingsDao().insertSetting(SettingsEntity("supabase_key", "old-unencrypted-secret"))
        assertEquals("https://protected.example" to "protected-secret",
            repository().getSupabaseCredentials())
        assertNull(database.settingsDao().getSettingValue("supabase_key"))
        assertNull(database.settingsDao().getSettingValue("supabase_url"))
    }
}
