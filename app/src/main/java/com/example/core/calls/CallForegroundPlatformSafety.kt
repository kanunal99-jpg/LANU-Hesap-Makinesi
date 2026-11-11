package com.example.core.calls

/**
 * Android/OEM foreground notification and service APIs may fail independently
 * of a valid encrypted call, even with permissions. Do not crash the process
 * or claim that microphone/camera foreground ownership was acquired.
 * Fatal VM failures are deliberately NOT swallowed.
 */
internal object CallForegroundPlatformSafety {
    fun execute(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    } catch (_: LinkageError) {
        false
    }
}
