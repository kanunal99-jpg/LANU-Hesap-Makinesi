package com.example.core.calls

import kotlinx.coroutines.CancellationException

/** Initialization only: never replaces an active call or replays an SDP offer. */
internal object CallAudioPipelineFactory {
    fun <M, E> build(
        decision: CallAudioProcessingPolicy.Decision,
        createModule: (CallAudioProcessingPolicy.Decision) -> M,
        createEngine: (M) -> E,
        releaseModule: (M) -> Unit
    ): Pair<M, E> {
        fun attempt(choice: CallAudioProcessingPolicy.Decision): Pair<M, E> {
            val module = createModule(choice)
            try { return module to createEngine(module) }
            catch (error: Throwable) {
                // Release also on linkage/cancellation, without masking the cause.
                try { releaseModule(module) } catch (_: RuntimeException) { } catch (_: LinkageError) { }
                throw error
            }
        }
        try { return attempt(decision) }
        catch (error: Throwable) {
            // Some vendor JavaAudioDeviceModule implementations throw a
            // LinkageError during hardware AEC/NS initialization, not merely
            // RuntimeException. A single fresh, software APM attempt is safe.
            // Never hide cancellation/permission, OutOfMemoryError, or repeat
            // when software is already selected. An unsuccessful fallback
            // propagates the real error rather than pretending audio exists.
            if (error !is RuntimeException && error !is LinkageError) throw error
            if (error is CancellationException || error is SecurityException) throw error
            val fallback = CallAudioProcessingPolicy.softwareFallback(decision)
            if (fallback == decision) throw error
            return attempt(fallback)
        }
    }
}
