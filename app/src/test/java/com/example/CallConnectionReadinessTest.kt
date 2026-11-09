package com.example
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.CallConnectionReadiness
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,31,34], application=Application::class)
class CallConnectionReadinessTest {
    @Test fun defaultsNeverClaimNotificationDeliveryOrRequestPermission() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val before = context.getSharedPreferences("lanu_call_wake_v1", 0).all.toMap()
        val state = CallConnectionReadiness.read(context)
        assertFalse(state.consent)
        assertEquals(before, context.getSharedPreferences("lanu_call_wake_v1", 0).all)
        assertTrue(state.summary().contains("Arama bildirimi tercihi: kapalı"))
        assertFalse(state.summary().contains("teslim edildi"))
    }
}
