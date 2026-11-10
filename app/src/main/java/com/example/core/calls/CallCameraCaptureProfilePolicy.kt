package com.example.core.calls

/**
 * Tested codec-friendly capture fallback order. Each attempt uses a fresh
 * camera handle after the previous OEM HAL attempt was released. Profiles
 * are requests, NOT verified output FPS/resolution or a promise of HD.
 *
 * Audio track is independent and never disabled by these fallback choices.
 */
internal object CallCameraCaptureProfilePolicy {
    data class Profile(val width: Int, val height: Int, val fps: Int)

    val profiles = listOf(
        Profile(1280, 720, 24),
        Profile(640, 480, 24),
        Profile(320, 240, 15)
    )

    fun valid(profile: Profile): Boolean =
        profile.width in 160..1920 && profile.height in 120..1080 &&
            profile.width % 2 == 0 && profile.height % 2 == 0 &&
            profile.fps in 10..30
}
