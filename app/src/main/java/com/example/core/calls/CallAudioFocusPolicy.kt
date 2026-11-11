package com.example.core.calls

import android.media.AudioManager

/**
 * A notification, navigation voice prompt or incoming system call may take
 * audio focus only briefly. Do not destroy a healthy authenticated WebRTC call
 * for a transient focus event. Capture stays muted until Android grants focus.
 *
 * Permanent loss may not emit a later GAIN; the session remains held and
 * requires explicit focus reacquisition instead of dropping the E2EE call.
 * Call/peer identifiers and audio samples are never recorded here.
 */
internal object CallAudioFocusPolicy {
    enum class Action { MUTE_AND_HOLD, RESTORE_IF_USER_UNMUTED, IGNORE }

    fun classify(change: Int): Action = when (change) {
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
        AudioManager.AUDIOFOCUS_LOSS -> Action.MUTE_AND_HOLD
        AudioManager.AUDIOFOCUS_GAIN -> Action.RESTORE_IF_USER_UNMUTED
        else -> Action.IGNORE
    }

    fun effectiveMute(userMuted: Boolean, temporarilyLostFocus: Boolean): Boolean =
        userMuted || temporarilyLostFocus
}
