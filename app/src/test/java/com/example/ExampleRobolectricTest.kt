package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
    assertEquals("LANU Hesap Makinesi", appName)
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
}
