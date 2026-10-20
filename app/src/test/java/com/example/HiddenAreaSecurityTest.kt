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
}
