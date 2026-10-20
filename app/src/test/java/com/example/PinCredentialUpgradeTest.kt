package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.crypto.CryptoUtils
import com.example.core.database.AppDatabase
import com.example.core.database.SettingsEntity
import com.example.core.repository.SecurityRepository
import com.example.core.security.PinCredentialCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinCredentialUpgradeTest {
    @Test fun `new PIN is salted per setup, PBKDF2, and never stored as a SHA256 legacy digest`() = runBlocking {
        val a = PinCredentialCodec.create("1234")
        val b = PinCredentialCodec.create("1234")
        assertTrue(a.startsWith("pbkdf2-sha256:210000:"))
        assertNotEquals(a, b)
        assertNotEquals(CryptoUtils.hashPin("1234"), a)
        assertTrue(PinCredentialCodec.verify("1234", a))
        assertFalse(PinCredentialCodec.verify("1235", a))
        assertFalse(PinCredentialCodec.verify("1234", "pbkdf2-sha256:0:bad:bad"))
        assertFalse(PinCredentialCodec.verify("1234", "plaintext-1234"))
    }

    @Test fun `legacy PIN is upgraded in place ONLY after successful authentication`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = SecurityRepository(context, db.settingsDao())
            val old = CryptoUtils.hashPin("9876")
            db.settingsDao().insertSetting(SettingsEntity("pin_hash", old))
            assertFalse(repo.verifyPin("1111"))
            assertEquals(old, db.settingsDao().getSettingValue("pin_hash"))
            assertTrue(repo.verifyPin("9876"))
            val upgraded = db.settingsDao().getSettingValue("pin_hash")!!
            assertTrue(PinCredentialCodec.isModern(upgraded))
            assertTrue(PinCredentialCodec.verify("9876", upgraded))
            assertFalse(PinCredentialCodec.verify("1111", upgraded))
            repo.lock()
            assertTrue(repo.verifyPin("9876"))
            assertEquals(upgraded, db.settingsDao().getSettingValue("pin_hash"))
        } finally { db.close() }
    }

    @Test fun `PIN replacement always rotates salt and preserves a locked session`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = SecurityRepository(context, db.settingsDao())
            assertTrue(repo.setupPin("1234"))
            val first = db.settingsDao().getSettingValue("pin_hash")
            repo.lock()
            assertTrue(repo.changePin("1234", "4321"))
            val second = db.settingsDao().getSettingValue("pin_hash")
            assertNotEquals(first, second)
            assertTrue(repo.isLockedState.value)
            assertFalse(repo.verifyPin("1234"))
            assertTrue(repo.verifyPin("4321"))
        } finally { db.close() }
    }

    @Test fun `legacy crypto helper refuses tampered content instead of returning it`() {
        val cipher = CryptoUtils.encrypt("gizli", "test-key")
        assertNotEquals("gizli", cipher)
        assertEquals("gizli", CryptoUtils.decrypt(cipher, "test-key"))
        try {
            CryptoUtils.decrypt("not-encrypted", "test-key")
            fail("Decryption of random content must fail closed")
        } catch (_: SecurityException) { }
    }
}
