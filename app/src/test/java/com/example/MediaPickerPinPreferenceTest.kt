package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.SettingsEntity
import com.example.core.repository.SecurityRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaPickerPinPreferenceTest {
    @Test fun persistedUserSettingDefaultsOffAndCanBeEnabledOrDisabled() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = SecurityRepository(context, db.settingsDao())
            // Existing users have no key; the new feature defaults to no extra PIN.
            assertFalse(repo.requirePinOnMediaPickerReturn())

            repo.setRequirePinOnMediaPickerReturn(true)
            assertTrue(repo.requirePinOnMediaPickerReturn())
            assertEquals("true", db.settingsDao().getSettingValue("pin_on_media_picker_return"))

            // State persists across a new repository instance; same user database.
            val reopenedRepo = SecurityRepository(context, db.settingsDao())
            assertTrue(reopenedRepo.requirePinOnMediaPickerReturn())
            reopenedRepo.setRequirePinOnMediaPickerReturn(false)
            assertFalse(repo.requirePinOnMediaPickerReturn())

            // Corrupt configuration may never silently bypass PIN.
            db.settingsDao().insertSetting(SettingsEntity("pin_on_media_picker_return", "BROKEN"))
            assertTrue(repo.requirePinOnMediaPickerReturn())
            // Existing PIN is never deleted or modified by this preference.
            db.settingsDao().insertSetting(SettingsEntity("pin_hash", "preserved-credential"))
            repo.setRequirePinOnMediaPickerReturn(false)
            assertEquals("preserved-credential", db.settingsDao().getSettingValue("pin_hash"))
        } finally { db.close() }
    }
}
