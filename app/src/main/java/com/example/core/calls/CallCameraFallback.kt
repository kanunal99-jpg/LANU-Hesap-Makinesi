package com.example.core.calls

import java.util.concurrent.CancellationException

/**
 * OEM camera capability is not a guarantee that the HAL can enumerate or open
 * a capturer. Try every enumerated front-facing then rear camera, then the
 * next *real* implementation (Camera2 -> Camera1) if one fails.
 *
 * No permission is requested here; only invoked after explicit user consent.
 * Never include device names / exception messages in privacy logs.
 */
internal object CallCameraFallback {
    data class Device(val name: String, val frontFacing: Boolean)

    fun <T : Any> select(
        enumerate: List<() -> List<Device>>,
        open: (sourceIndex: Int, device: Device) -> T?
    ): T? {
        // Broken OEM enumerators may report thousands of duplicate devices.
        // Bound expensive native open/start retries; prefer physical front
        // cameras without sorting or copying the entire reported list.
        for ((index, source) in enumerate.take(4).withIndex()) {
            val devices = try { source() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { continue }
            val front = devices.asSequence().filter { it.frontFacing }.take(16)
            val rear = devices.asSequence().filterNot { it.frontFacing }.take(16)
            for (device in front + rear) {
                val selected = try { open(index, device) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                if (selected != null) return selected
            }
        }
        return null
    }
}
