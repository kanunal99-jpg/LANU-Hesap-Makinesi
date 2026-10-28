package com.example.core.calls

/**
 * OEM camera switches are asynchronous. Allow only one switch at a time;
 * a delayed callback from an old switch must never complete a new one.
 * No device names, exception text, camera images or private state are stored.
 */
internal class CallCameraSwitchGate {
    private var generation = 0L
    private var pending: Long? = null
    // This gate belongs to one NativeCallEngine; closing it is terminal.
    private var closed = false

    @Synchronized
    fun begin(canSwitch: Boolean): Long? {
        if (closed || !canSwitch || pending != null) return null
        generation++
        pending = generation
        return generation
    }

    /** Returns false if the callback belongs to a superseded/ended switch. */
    @Synchronized
    fun finish(ticket: Long): Boolean {
        if (pending != ticket) return false
        pending = null
        return true
    }

    /** Call closure invalidates all callbacks still owned by the OEM HAL. */
    @Synchronized
    fun invalidate() {
        closed = true
        generation++
        pending = null
    }
}
