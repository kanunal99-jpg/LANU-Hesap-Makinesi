package com.example.features.communication

/**
 * VoiceOver/TalkBack phrases describe the action that will happen, not a
 * frozen icon name. No contact, PIN or call identifier is exposed.
 */
internal object PrivateCallControlDescriptionPolicy {
    fun microphoneToggle(muted: Boolean): String =
        if (muted) "Mikrofonu aç" else "Mikrofonu sustur"

    fun cameraToggle(cameraOn: Boolean): String =
        if (cameraOn) "Kamerayı kapat" else "Kamerayı aç"

    const val CAMERA_SWITCH = "Ön ve arka kamera arasında geçiş yap"
}
