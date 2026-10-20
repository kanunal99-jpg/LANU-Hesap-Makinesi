package com.example

import android.app.Application
import com.example.core.di.AppContainer
import com.example.core.security.LegacyInsecurePreferencesCleanup

class LanuApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        LegacyInsecurePreferencesCleanup.clean(this)
        container = AppContainer(this)
    }
}
