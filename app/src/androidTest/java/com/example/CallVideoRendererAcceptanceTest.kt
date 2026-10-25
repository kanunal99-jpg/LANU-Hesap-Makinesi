package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.calls.CallVideoRendererBindings
import com.example.core.calls.WebRtcEglFactory
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

/** Exercises the production renderer owner, including main-thread init and EGL-safe teardown. */
@RunWith(AndroidJUnit4::class)
class CallVideoRendererAcceptanceTest {
    @Test fun realRendererCanBindFromWorkerAndReleaseBeforeEglOnDebugAndRelease() = runBlocking {
        withTimeout(15_000L) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val egl = withContext(Dispatchers.Default) { WebRtcEglFactory.create() }
            val owner = CallVideoRendererBindings(egl.eglBaseContext)
            val local = withContext(Dispatchers.Main) { SurfaceViewRenderer(context) }
            val remote = withContext(Dispatchers.Main) { SurfaceViewRenderer(context) }
            try {
                withContext(Dispatchers.Default) {
                    owner.bind(local, false); owner.bind(remote, true)
                    owner.tracks(null, null)
                    owner.unbind(local); owner.bind(local, false)
                    owner.close(); owner.close()
                    owner.bind(remote, true) // Closed owners must never reinitialize a renderer.
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Default) { owner.close(); egl.release() }
            }
        }
    }
}
