package com.example.core.calls

import android.media.AudioManager

/**
 * A notification, navigation voice prompt or incoming system call may take
 * audio focus only briefly. Do not destroy a healthy authenticated WebRTC call
 * for a transient focus event. Capture stays muted until Android grants focus.
 *
 * Permanent loss still closes the call; a transient wait is strictly bounded.
 * Call/peer identifiers and audio samples are never recorded here.
 */
internal object CallAudioFocusPolicy {
    enum class Action { MUTE_AND_HOLD, RESTORE_IF_USER_UNMUTED, TERMINATE, IGNORE }

    const val MAX_TRANSIENT_LOSS_MS = 45_000L

    fun classify(change: Int): Action = when (change) {
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Action.MUTE_AND_HOLD
        AudioManager.AUDIOFOCUS_GAIN -> Action.RESTORE_IF_USER_UNMUTED
        AudioManager.AUDIOFOCUS_LOSS -> Action.TERMINATE
        else -> Action.IGNORE
    }

    fun effectiveMute(userMuted: Boolean, temporarilyLostFocus: Boolean): Boolean =
        userMuted || temporarilyLostFocus
}
