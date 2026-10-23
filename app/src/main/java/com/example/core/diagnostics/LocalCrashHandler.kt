package com.example.core.diagnostics

import android.content.Context
import android.os.Process
import kotlin.system.exitProcess

/**
 * Crash recording is strictly local and best-effort. It is not a substitute
 * for Android's normal crash/ANR reporting and never swallows fatal failures.
 */
object LocalCrashHandler {
    @Volatile private var installed: Boolean = false

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val appContext = context.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                LocalCrashReport.save(appContext, throwable)
            } catch (_: Throwable) {
                // Out-of-memory or I/O failure: preserve original crash handling.
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
        installed = true
    }
}
