package com.example

import android.content.Context
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

/** Mandatory owner authentication, including upgrades from optional-PIN installs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OptionalPrivatePinTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: SecurityRepository
    private lateinit var context: Context
    private var timeMs = 1_700_000_000_000L

    private fun newRepo(): SecurityRepository = SecurityRepository(
        context, db.settingsDao(),
        preferencesForTesting = context.getSharedPreferences("test_optional_pin_prefs", Context.MODE_PRIVATE),
        clock = { timeMs }
    )

    @Before fun init() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = newRepo()
    }

    @After fun stop() { db.close() }

    @Test fun noPinRequiresSetupAndNeverUnlocksFromGesture() = runBlocking {
        assertFalse(repo.hasPin())
        assertTrue(repo.isEntryPinEnabled())
        assertTrue(repo.isLockedState.value)
        assertFalse(repo.tryEnterWithoutPin())
        assertTrue(repo.isLockedState.value)
        assertFalse(newRepo().tryEnterWithoutPin())
        assertTrue(newRepo().isLockedState.value)
        assertTrue(repo.setupPin("4826"))
        repo.lock()
        assertFalse(repo.tryEnterWithoutPin())
        assertTrue(repo.verifyPin("4826"))
    }

    @Test fun legacyOptOutRetainsExistingCredentialButCannotBypassIt() = runBlocking {
        assertTrue(repo.setupPin("5639"))
        val savedHash = db.settingsDao().getSettingValue("pin_hash")
        db.settingsDao().insertSetting(SettingsEntity("entry_pin_enabled", "false"))
        repo.lock()
        assertTrue(repo.isEntryPinEnabled())
        assertFalse(repo.tryEnterWithoutPin())
        assertTrue(repo.isLockedState.value)
        val restarted = newRepo()
        assertFalse(restarted.tryEnterWithoutPin())
        assertTrue(restarted.isLockedState.value)
        assertEquals(savedHash, db.settingsDao().getSettingValue("pin_hash"))
        assertEquals("false", db.settingsDao().getSettingValue("entry_pin_enabled"))
        assertTrue(restarted.verifyPin("5639"))
    }

    @Test fun disablingMandatoryEntryNeverErasesOrChangesThePin() = runBlocking {
        assertTrue(repo.setupPin("5639"))
        val hash = db.settingsDao().getSettingValue("pin_hash")
        repo.lock()
        assertFalse(repo.setEntryPinEnabled(false))
        assertFalse(repo.setEntryPinEnabled(false, "5639"))
        assertTrue(repo.isLockedState.value)
        assertEquals(hash, db.settingsDao().getSettingValue("pin_hash"))
        assertTrue(repo.verifyPin("5639"))
    }

    @Test fun reenableLegacyPreferenceWithoutCredentialReset() = runBlocking {
        assertTrue(repo.setupPin("7777"))
        db.settingsDao().insertSetting(SettingsEntity("entry_pin_enabled", "false"))
        assertTrue(repo.changePin("7777", "987654"))
        assertTrue(repo.setEntryPinEnabled(true))
        assertEquals("true", db.settingsDao().getSettingValue("entry_pin_enabled"))
        repo.lock()
        assertFalse(newRepo().tryEnterWithoutPin())
        assertFalse(repo.verifyPin("7777"))
        assertTrue(repo.verifyPin("987654"))
    }

    @Test fun corruptLegacyPreferenceAndRestoreStayFailClosed() = runBlocking {
        assertTrue(repo.setupPin("4826"))
        db.settingsDao().insertSetting(SettingsEntity("entry_pin_enabled", "invalid"))
        repo.lock()
        assertTrue(repo.isEntryPinEnabled())
        assertFalse(repo.tryEnterWithoutPin())
        assertFalse(newRepo().tryEnterWithoutPin())
        assertTrue(newRepo().verifyPin("4826"))
    }

    @Test fun lockoutCannotBeBypassedByLegacyOptOutOrRestart() = runBlocking {
        assertTrue(repo.setupPin("4826"))
        db.settingsDao().insertSetting(SettingsEntity("entry_pin_enabled", "false"))
        repo.lock()
        repeat(5) { assertFalse(repo.verifyPin("1111")) }
        assertFalse(newRepo().tryEnterWithoutPin())
        assertFalse(newRepo().verifyPin("4826"))
        assertTrue(newRepo().isLockedState.value)
        timeMs += 30_000L
        assertTrue(newRepo().verifyPin("4826"))
    }

    @Test fun pinlessBackgroundAndNewProcessRemainLocked() = runBlocking {
        assertFalse(repo.tryEnterWithoutPin())
        repo.setLockTimeout(0)
        repo.handleAppBackgrounded()
        assertTrue(repo.isLockedState.value)
        assertTrue(newRepo().isLockedState.value)
        assertFalse(newRepo().tryEnterWithoutPin())
    }
}
