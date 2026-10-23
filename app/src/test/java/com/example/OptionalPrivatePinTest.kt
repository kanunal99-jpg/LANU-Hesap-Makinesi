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

/**
 * First hidden-area entry is always via the calculator secret gesture.
 * These tests exercise the repository's independent optional PIN gate.
 */
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

    @Test fun noPinIsNotForcedOnFirstHiddenEntry() = runBlocking {
        assertFalse(repo.hasPin())
        assertFalse(repo.isEntryPinEnabled())
        assertTrue(repo.isLockedState.value)
        assertTrue(repo.tryEnterWithoutPin())
        assertFalse(repo.isLockedState.value)
        repo.lock()
        assertTrue(repo.isLockedState.value)
        assertTrue(newRepo().tryEnterWithoutPin())
    }

    @Test fun existingPinContinuesToBlockPasswordlessEntryByDefault() = runBlocking {
        assertTrue(repo.setupPin("5639"))
        repo.lock()
        assertTrue(repo.isEntryPinEnabled())
        assertFalse(repo.tryEnterWithoutPin())
        assertTrue(repo.isLockedState.value)
        val restarted = newRepo()
        assertTrue(restarted.isEntryPinEnabled())
        assertFalse(restarted.tryEnterWithoutPin())
        assertTrue(restarted.isLockedState.value)
    }

    @Test fun disableRequiresRealPinAndPreservesCredentialAcrossRestart() = runBlocking {
        assertTrue(repo.setupPin("5639"))
        repo.lock()
        assertFalse(repo.setEntryPinEnabled(false))
        assertFalse(repo.setEntryPinEnabled(false, "2011"))
        assertTrue(repo.isEntryPinEnabled())
        assertTrue(repo.setEntryPinEnabled(false, "5639"))
        assertFalse(repo.isEntryPinEnabled())
        assertTrue(repo.hasPin())
        val restarted = newRepo()
        assertTrue(restarted.tryEnterWithoutPin())
        restarted.lock()
        assertTrue(restarted.verifyPin("5639"))
    }

    @Test fun reenablingPinUsesExistingCredentialWithoutResettingIt() = runBlocking {
        assertTrue(repo.setupPin("7777"))
        assertTrue(repo.setEntryPinEnabled(false, "7777"))
        assertTrue(repo.changePin("7777", "987654"))
        assertFalse(repo.isEntryPinEnabled())
        assertTrue(repo.setEntryPinEnabled(true))
        repo.lock()
        assertFalse(newRepo().tryEnterWithoutPin())
        assertFalse(repo.verifyPin("7777"))
        assertTrue(repo.verifyPin("987654"))
    }

    @Test fun deliberatePinCreationInSettingsEnablesGate() = runBlocking {
        assertFalse(repo.isEntryPinEnabled())
        assertFalse(repo.setEntryPinEnabled(true))
        assertFalse(repo.hasPin())
        assertTrue(repo.setupPin("4826"))
        assertTrue(repo.isEntryPinEnabled())
        repo.lock()
        assertFalse(repo.tryEnterWithoutPin())
        assertTrue(repo.verifyPin("4826"))
    }

    @Test fun fiveInvalidDisableAttemptsCannotBypassExistingBruteForceLockout() = runBlocking {
        assertTrue(repo.setupPin("4826"))
        repo.lock()
        repeat(5) { assertFalse(repo.setEntryPinEnabled(false, "1111")) }
        assertFalse(repo.setEntryPinEnabled(false, "4826"))
        assertTrue(repo.isEntryPinEnabled())
        assertFalse(repo.tryEnterWithoutPin())
        timeMs += 30_000
        assertTrue(repo.setEntryPinEnabled(false, "4826"))
        assertTrue(newRepo().tryEnterWithoutPin())
    }

    @Test fun corruptPreferenceRequiresPinRatherThanSilentlyDisablingIt() = runBlocking {
        assertTrue(repo.setupPin("4826"))
        db.settingsDao().insertSetting(SettingsEntity("entry_pin_enabled", "invalid"))
        repo.lock()
        assertTrue(repo.isEntryPinEnabled())
        assertFalse(repo.tryEnterWithoutPin())
        assertTrue(repo.verifyPin("4826"))
    }

    @Test fun pinlessUnlockedSessionStillLocksOnScreenOffOrBackground() = runBlocking {
        assertTrue(repo.tryEnterWithoutPin())
        repo.lock()
        assertTrue(repo.isLockedState.value)
        assertTrue(repo.tryEnterWithoutPin())
        repo.setLockTimeout(0)
        repo.handleAppBackgrounded()
        assertTrue(repo.isLockedState.value)
    }
}
