package com.example.core.calls

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
        for ((index, source) in enumerate.withIndex()) {
            val devices = try { source() } catch (_: Exception) { continue }
            for (device in devices.sortedBy { if (it.frontFacing) 0 else 1 }) {
                val selected = try { open(index, device) } catch (_: Exception) { null }
                if (selected != null) return selected
            }
        }
        return null
    }
}
