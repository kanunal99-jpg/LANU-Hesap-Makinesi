package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.crypto.CryptoUtils
import com.example.core.database.AppDatabase
import com.example.core.database.SettingsEntity
import com.example.core.repository.SecurityRepository
import com.example.core.security.PinPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenAreaSecurityTest {
    private lateinit var database: AppDatabase
    private lateinit var context: Context
    private lateinit var repository: SecurityRepository
    private var now = 1_000_000L

    // The JVM test supplies a fake preference store; production never accepts a plaintext fallback.
    private fun newRepository() = SecurityRepository(
        context, database.settingsDao(),
        preferencesForTesting = context.getSharedPreferences("test_only_biometric_flags", Context.MODE_PRIVATE),
        clock = { now }
    )

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = newRepository()
        repository.setBiometricEnabled(false)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `PIN policy accepts ASCII digits from four to twelve only`() {
        assertTrue(PinPolicy.isValid("0000"))
        assertTrue(PinPolicy.isValid("123456789012"))
        listOf("", "123", "1234567890123", "abcd", "12 34", "2011.", "１２３４").forEach {
            assertFalse("Must reject $it", PinPolicy.isValid(it))
        }
    }

    @Test
    fun `new process starts locked even before setup`() {
        assertTrue(repository.isLockedState.value)
    }

    @Test
    fun `setup and twelve digit PIN verification work`() = runBlocking {
        assertTrue(repository.setupPin("123456789012"))
        repository.lock()
        assertTrue(repository.verifyPin("123456789012"))
        assertFalse(repository.isLockedState.value)
    }

    @Test
    fun `setup cannot overwrite existing PIN`() = runBlocking {
        assertTrue(repository.setupPin("1234"))
        assertFalse(repository.setupPin("9876"))
        repository.lock()
        assertFalse(repository.verifyPin("9876"))
        assertTrue(repository.verifyPin("1234"))
    }

    @Test
    fun `invalid setup never creates a credential`() = runBlocking {
        assertFalse(repository.setupPin("abcd"))
        assertFalse(repository.hasPin())
        assertTrue(repository.isLockedState.value)
    }

    @Test
    fun `legacy PIN hashes continue to unlock`() = runBlocking {
        database.settingsDao().insertSetting(SettingsEntity("pin_hash", CryptoUtils.hashPin("2011")))
        assertTrue(repository.verifyPin("2011"))
    }

    @Test
    fun `entry code never bypasses configured PIN`() = runBlocking {
        repository.setupPin("9876")
        repository.lock()
        assertFalse(repository.verifyPin("2011."))
        assertTrue(repository.isLockedState.value)
    }

    @Test
    fun `five wrong attempts block even the correct PIN`() = runBlocking {
        repository.setupPin("1234")
        repository.lock()
        repeat(5) { assertFalse(repository.verifyPin("9999")) }
        assertEquals(30, repository.getPinAttemptStatus().remainingSeconds)
        assertFalse(repository.verifyPin("1234"))
        assertTrue(repository.isLockedState.value)
    }

    @Test
    fun `partial attempt counter survives repository recreation`() = runBlocking {
        repository.setupPin("1234")
        repeat(4) { repository.verifyPin("9999") }
        val recreated = newRepository()
        assertEquals(4, recreated.getPinAttemptStatus().failedAttempts)
        assertFalse(recreated.verifyPin("9999"))
        assertEquals(30, recreated.getPinAttemptStatus().remainingSeconds)
    }

    @Test
    fun `lockout survives recreation and expires at the recorded deadline`() = runBlocking {
        repository.setupPin("1234")
        repeat(5) { repository.verifyPin("9999") }
        now += 10_500L
        val recreated = newRepository()
        assertEquals(20, recreated.getPinAttemptStatus().remainingSeconds)
        assertFalse(recreated.verifyPin("1234"))
        now += 19_500L
        assertEquals(0, recreated.getPinAttemptStatus().remainingSeconds)
        assertTrue(recreated.verifyPin("1234"))
    }

    @Test
    fun `blocked attempts do not extend waiting time`() = runBlocking {
        repository.setupPin("1234")
        repeat(5) { repository.verifyPin("9999") }
        now += 29_000L
        repeat(10) { assertFalse(repository.verifyPin("9999")) }
        assertEquals(1, repository.getPinAttemptStatus().remainingSeconds)
        now += 1_000L
        assertTrue(repository.verifyPin("1234"))
    }

    @Test
    fun `successful verification clears previous failures`() = runBlocking {
        repository.setupPin("1234")
        repeat(4) { repository.verifyPin("9999") }
        assertTrue(repository.verifyPin("1234"))
        assertEquals(0, newRepository().getPinAttemptStatus().failedAttempts)
    }

    @Test
    fun `parallel attempts cannot lose increments or escape lockout`() = runBlocking {
        repository.setupPin("1234")
        repository.lock()
        (1..12).map { async(Dispatchers.Default) { repository.verifyPin("9999") } }.awaitAll()
        assertEquals(30, repository.getPinAttemptStatus().remainingSeconds)
        assertFalse(repository.verifyPin("1234"))
    }

    @Test
    fun `PIN change rejects nonnumeric and unreachable lengths`() = runBlocking {
        repository.setupPin("1234")
        listOf("abcd", "123", "1234567890123").forEach {
            assertFalse(repository.changePin("1234", it))
        }
        assertTrue(repository.verifyPin("1234"))
    }

    @Test
    fun `PIN change uses the same durable attempt limit`() = runBlocking {
        repository.setupPin("1234")
        repeat(5) { assertFalse(repository.changePin("9999", "567890")) }
        assertFalse(newRepository().changePin("1234", "567890"))
        now += PinPolicy.LOCKOUT_MS
        assertTrue(repository.changePin("1234", "567890"))
        assertFalse(repository.verifyPin("1234"))
        assertTrue(repository.verifyPin("567890"))
    }

    @Test
    fun `PIN change alone does not unlock a locked session`() = runBlocking {
        repository.setupPin("1234")
        repository.lock()
        assertTrue(repository.changePin("1234", "5678"))
        assertTrue(repository.isLockedState.value)
    }

    @Test
    fun `biometric callback requires a configured PIN and explicit opt in`() = runBlocking {
        repository.setBiometricEnabled(true)
        assertFalse(repository.completeBiometricUnlock())
        repository.setupPin("1234")
        repository.setBiometricEnabled(false)
        repository.lock()
        assertFalse(repository.completeBiometricUnlock())
        assertTrue(repository.isLockedState.value)
    }

    @Test
    fun `enabled biometric callback updates the authoritative lock state`() = runBlocking {
        repository.setupPin("1234")
        repository.setBiometricEnabled(true)
        repository.lock()
        assertTrue(repository.completeBiometricUnlock())
        assertFalse(repository.isLockedState.value)
    }

    @Test
    fun `biometric callback cannot bypass an active PIN lockout`() = runBlocking {
        repository.setupPin("1234")
        repository.setBiometricEnabled(true)
        repository.lock()
        repeat(5) { repository.verifyPin("9999") }
        assertFalse(repository.completeBiometricUnlock())
        assertTrue(repository.isLockedState.value)
    }

    @Test
    fun `corrupt attempt state fails closed for a bounded waiting period`() = runBlocking {
        repository.setupPin("1234")
        repository.lock()
        database.settingsDao().insertSetting(SettingsEntity("pin_attempt_state", "corrupt"))
        assertFalse(repository.verifyPin("1234"))
        assertEquals(30, repository.getPinAttemptStatus().remainingSeconds)
        now += PinPolicy.LOCKOUT_MS
        assertTrue(repository.verifyPin("1234"))
    }

    @Test
    fun `immediate background lock uses the authoritative state`() = runBlocking {
        repository.setupPin("1234")
        repository.setLockTimeout(0)
        repository.handleAppBackgrounded()
        assertTrue(repository.isLockedState.value)
    }

    @Test
    fun `foreground callback never unlocks a cold session`() = runBlocking {
        repository.handleAppForegrounded()
        assertTrue(repository.isLockedState.value)
        repository.setupPin("1234")
        assertTrue(newRepository().isLockedState.value)
    }

    @Test fun `second brute force burst escalates lock and survives process recreation`() = runBlocking {
        assertTrue(repository.setupPin("1234"))
        repeat(5) { assertFalse(repository.verifyPin("9999")) }
        assertEquals(30, repository.getPinAttemptStatus().remainingSeconds)
        assertEquals("1", database.settingsDao().getSettingValue("pin_lockout_strikes"))
        now += PinPolicy.LOCKOUT_MS
        val afterRestart = newRepository()
        repeat(5) { assertFalse(afterRestart.verifyPin("9999")) }
        assertEquals(120, newRepository().getPinAttemptStatus().remainingSeconds)
        assertEquals("2", database.settingsDao().getSettingValue("pin_lockout_strikes"))
        now += 119_000L
        assertFalse(newRepository().verifyPin("1234"))
        assertEquals(1, repository.getPinAttemptStatus().remainingSeconds)
        now += 1_000L
        assertTrue(newRepository().verifyPin("1234"))
        assertEquals("0", database.settingsDao().getSettingValue("pin_lockout_strikes"))
        repeat(5) { assertFalse(newRepository().verifyPin("9999")) }
        assertEquals(30, newRepository().getPinAttemptStatus().remainingSeconds)
    }

    @Test fun `progressive PIN backoff is capped and malformed strike counter fails closed`() = runBlocking {
        assertEquals(30_000L, PinPolicy.lockoutForStrike(1))
        assertEquals(120_000L, PinPolicy.lockoutForStrike(2))
        assertEquals(600_000L, PinPolicy.lockoutForStrike(3))
        assertEquals(3_600_000L, PinPolicy.lockoutForStrike(4))
        assertEquals(PinPolicy.MAX_LOCKOUT_MS, PinPolicy.lockoutForStrike(5))
        assertEquals(PinPolicy.MAX_LOCKOUT_MS, PinPolicy.lockoutForStrike(Int.MAX_VALUE))
        assertTrue(repository.setupPin("1234"))
        database.settingsDao().insertSetting(SettingsEntity("pin_lockout_strikes", "corrupted"))
        repeat(5) { assertFalse(repository.verifyPin("9999")) }
        assertEquals(43_200, newRepository().getPinAttemptStatus().remainingSeconds)
        assertEquals("5", database.settingsDao().getSettingValue("pin_lockout_strikes"))
        assertFalse(repository.verifyPin("1234"))
    }

    @Test fun `failed PIN escalation commits neither deadline nor tier and can recover`() = runBlocking {
        assertTrue(repository.setupPin("1234"))
        repeat(4) { assertFalse(repository.verifyPin("0000")) }
        assertEquals(4, repository.getPinAttemptStatus().failedAttempts)
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            """CREATE TRIGGER fail_strike_write BEFORE INSERT ON app_settings
                WHEN NEW.`key` = 'pin_lockout_strikes'
                BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END"""
        )
        try {
            repository.verifyPin("0000")
            fail("Partial escalation must roll back instead of persisting one row")
        } catch (_: Exception) {
            // This test deliberately injects SQLite failure in the second write.
        } finally {
            sql.execSQL("DROP TRIGGER IF EXISTS fail_strike_write")
        }
        assertEquals("Four earlier failures must survive a rejected transaction",
            "4:0", database.settingsDao().getSettingValue("pin_attempt_state"))
        assertEquals("0", database.settingsDao().getSettingValue("pin_lockout_strikes"))
        val restarted = newRepository()
        assertFalse(restarted.verifyPin("0000"))
        assertEquals(30, restarted.getPinAttemptStatus().remainingSeconds)
        assertEquals("1", database.settingsDao().getSettingValue("pin_lockout_strikes"))
    }

    @Test fun `successful biometric unlock clears lockout and strike in one transaction`() = runBlocking {
        assertTrue(repository.setupPin("1234"))
        repository.setBiometricEnabled(true)
        repeat(4) { assertFalse(repository.verifyPin("0000")) }
        assertTrue(repository.completeBiometricUnlock())
        assertEquals("0:0", database.settingsDao().getSettingValue("pin_attempt_state"))
        assertEquals("0", database.settingsDao().getSettingValue("pin_lockout_strikes"))
        assertEquals(0, newRepository().getPinAttemptStatus().failedAttempts)
    }

    @Test fun `failed successful-PIN reset rolls back both guard fields and stays locked`() = runBlocking {
        assertTrue(repository.setupPin("1234"))
        repository.lock()
        repeat(4) { assertFalse(repository.verifyPin("9999")) }
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            """CREATE TRIGGER deny_pin_guard_reset BEFORE INSERT ON app_settings
                WHEN NEW.`key` = 'pin_lockout_strikes'
                BEGIN SELECT RAISE(ABORT, 'simulated PIN guard persistence failure'); END"""
        )
        try {
            repository.verifyPin("1234")
            fail("The PIN must never be reported as an unlocked session on partial persistence")
        } catch (_: Exception) {
            // Injected failure happens after writing attempt state in the transaction.
        } finally {
            sql.execSQL("DROP TRIGGER IF EXISTS deny_pin_guard_reset")
        }
        assertEquals("4:0", database.settingsDao().getSettingValue("pin_attempt_state"))
        assertEquals("0", database.settingsDao().getSettingValue("pin_lockout_strikes"))
        assertTrue(repository.isLockedState.value)
        assertTrue(newRepository().verifyPin("1234"))
        assertEquals("0:0", database.settingsDao().getSettingValue("pin_attempt_state"))
        assertEquals("0", database.settingsDao().getSettingValue("pin_lockout_strikes"))
    }

    @Test fun `successful correct PIN resets the entire progressive guard in one commit`() = runBlocking {
        assertTrue(repository.setupPin("1234"))
        repeat(5) { assertFalse(repository.verifyPin("9999")) }
        now += PinPolicy.LOCKOUT_MS
        assertTrue(repository.verifyPin("1234"))
        assertEquals("0:0", database.settingsDao().getSettingValue("pin_attempt_state"))
        assertEquals("0", database.settingsDao().getSettingValue("pin_lockout_strikes"))
        repeat(5) { assertFalse(newRepository().verifyPin("9999")) }
        assertEquals(30, repository.getPinAttemptStatus().remainingSeconds)
    }
}
