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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
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
class SecurityStartupFailureTest {
    @Test fun `settings IO failure stays locked without uncaught crash and retry can recover`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val owner = ViewModelStore()
        try {
            var failing = true
            val dao = object : SettingsDao by db.settingsDao() {
                override suspend fun getSettingValue(key: String): String? {
                    if (failing && key == "lock_timeout") throw java.io.IOException("test disk failure")
                    return db.settingsDao().getSettingValue(key)
                }
            }
            val repository = SecurityRepository(context, dao,
                preferencesForTesting = context.getSharedPreferences("test_startup_preferences", Context.MODE_PRIVATE))
            val model = SecurityViewModel(repository, SecurityEventTracker(context))
            owner.put("security", model)
            withTimeout(5000) { model.errorMessage.filterNotNull().first() }
            assertFalse(model.isReady.value)
            assertTrue(repository.isLockedState.value)
            var opened = false
            model.enterFromCalculator({ opened = true }, {})
            assertFalse(opened)
            failing = false
            model.reloadAfterRestore()
            withTimeout(5000) { model.isReady.filter { it }.first() }
            assertTrue(model.isReady.value)
            assertFalse(repository.hasPin())
        } finally { owner.clear(); db.close(); Dispatchers.resetMain() }
    }
}
