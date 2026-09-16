package com.example

import android.app.Application
import com.example.core.di.AppContainer

class LanuApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
