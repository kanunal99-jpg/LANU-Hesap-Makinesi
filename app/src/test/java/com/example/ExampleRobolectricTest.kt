package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("LANU", appName)
  }

  @Test
  fun `confirmation dialog strings are present`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val clearHistoryTitle = context.getString(R.string.clear_all_history_title)
    val resetCalcTitle = context.getString(R.string.reset_calculation_title)
    val resetConfirm = context.getString(R.string.reset_calculation_confirm)
    
    assertEquals("Tüm Geçmişi Temizle", clearHistoryTitle)
    assertEquals("Hesaplamayı Sıfırla", resetCalcTitle)
    assertEquals("Sıfırla", resetConfirm)
  }
  @Test
  fun `call demo never claims a real WebRTC connection`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val labels = listOf(
      context.getString(R.string.secure_video_call_title),
      context.getString(R.string.secure_voice_call_title),
      context.getString(R.string.call_connected, "00:10"),
      context.getString(R.string.webrtc_active_stream),
      context.getString(R.string.call_demo_disclaimer)
    )
    assertTrue(labels.all { it.contains("demo", ignoreCase = true) || it.contains("simülasyon", ignoreCase = true) })
    assertTrue(context.getString(R.string.call_demo_disclaimer).contains("Gerçek arama"))
  }

}
