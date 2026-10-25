package com.example.core.calls

import androidx.annotation.Keep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** UI-thread initialization; serialized teardown completes before the owning EGL is released. */
@Keep
class CallVideoRendererBindings(private val context: EglBase.Context) {
    private data class Binding(val renderer: SurfaceViewRenderer, val remote: Boolean, var track: VideoTrack? = null)
    private val mutex = Mutex()
    private val bindings = mutableListOf<Binding>()
    private var closed = false
    private var local: VideoTrack? = null
    private var remote: VideoTrack? = null

    suspend fun bind(renderer: SurfaceViewRenderer, isRemote: Boolean) = mutex.withLock {
        if (closed || bindings.any { it.renderer === renderer }) return@withLock
        // SurfaceViewRenderer.init explicitly requires Android's main thread.
        try {
            withContext(Dispatchers.Main.immediate) {
                renderer.init(context, null)
                renderer.setMirror(!isRemote)
            }
        } catch (error: Throwable) {
            runCatching { renderer.release() }
            throw error
        }
        bindings += Binding(renderer, isRemote)
        update()
    }
    suspend fun tracks(local: VideoTrack?, remote: VideoTrack?) = mutex.withLock {
        if (closed) return@withLock
        this.local = local; this.remote = remote
        update()
    }
    private fun update() {
        for (b in bindings) {
            val next = if (b.remote) remote else local
            if (b.track !== next) {
                runCatching { b.track?.removeSink(b.renderer) }
                b.track = next
                next?.addSink(b.renderer)
            }
        }
    }
    suspend fun unbind(renderer: SurfaceViewRenderer) = mutex.withLock {
        bindings.filter { it.renderer === renderer }.forEach(::release)
        bindings.removeAll { it.renderer === renderer }
    }
    suspend fun close() = mutex.withLock {
        if (closed) return@withLock
        closed = true
        bindings.forEach(::release); bindings.clear()
        local = null; remote = null
    }
    private fun release(binding: Binding) {
        runCatching { binding.track?.removeSink(binding.renderer) }
        runCatching { binding.renderer.release() }
    }
}

/**
 * Shared production EGL entry point. Both the actual call engine and the
 * separately compiled release instrumentation APK use this SAME target-APK
 * method. Calling EglBase.create() directly from androidTest can link against
 * an incompatible desugared static-interface bridge on API 23 minSdk builds.
 * Never suppress the real renderer test or switch off R8 to hide that failure.
 */
@Keep
object WebRtcEglFactory {
    @JvmStatic
    @Keep
    fun create(): EglBase = EglBase.create()
}
