package com.example.core.calls

/**
 * Tested codec-friendly capture fallback order. Each attempt uses a fresh
 * camera handle after the previous OEM HAL attempt was released. Profiles
 * are requests, NOT verified output FPS/resolution or a promise of HD.
 *
 * Audio track is independent and never disabled by these fallback choices.
 */
internal object CallCameraCaptureProfilePolicy {
    // A few broken OEM cameras can otherwise multiply 3 profiles by the
    // entire 32-device fallback list. Reserve the SAME budget for Camera1:
    // no single Camera2 HAL can consume another provider's attempts.
    const val MAX_ATTEMPTS_PER_HAL = 12
    fun mayAttempt(attempts: Int): Boolean = attempts in 0 until MAX_ATTEMPTS_PER_HAL

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
