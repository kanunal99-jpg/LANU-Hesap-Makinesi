package com.example

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.SettingsDao
import com.example.core.repository.SecurityRepository
import com.example.core.security.SecurityEventTracker
import com.example.features.security.SecurityViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivateEntryLockRaceTest {
    @Test fun screenLockDuringSuspendedPasswordlessEntryCannotReopenPrivateArea() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val owner = ViewModelStore()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pauseEntry = false
        try {
            val delegate = db.settingsDao()
            val dao = object : SettingsDao by delegate {
                override suspend fun getSettingValue(key: String): String? {
                    if (key == "entry_pin_enabled" && pauseEntry) {
                        entered.complete(Unit)
                        release.await()
                    }
                    return delegate.getSettingValue(key)
                }
            }
            val repo = SecurityRepository(
                context, dao,
                preferencesForTesting = context.getSharedPreferences("test_private_entry_lock_race", Context.MODE_PRIVATE))
            val model = SecurityViewModel(repo, SecurityEventTracker(context))
            owner.put("security", model)
            withTimeout(5000) { model.isReady.filter { it }.first() }
            assertFalse(repo.hasPin())
            var opened = false
            var pinPrompted = false
            pauseEntry = true
            model.enterFromCalculator({ opened = true }, { pinPrompted = true })
            withTimeout(5000) { entered.await() }
            model.lock()
            release.complete(Unit)
            withTimeout(5000) {
                while (repo.isLockedState.value.not()) delay(10)
            }
            // Let any remaining coroutine dispatch complete before testing state.
            delay(50)
            assertFalse(opened)
            assertFalse(pinPrompted)
            assertTrue(repo.isLockedState.value)
            assertFalse(model.isLocked.value.not())
        } finally {
            release.complete(Unit)
            owner.clear()
            db.close()
            Dispatchers.resetMain()
        }
    }
}
