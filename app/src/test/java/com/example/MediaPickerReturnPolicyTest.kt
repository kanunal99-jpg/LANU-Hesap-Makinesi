package com.example

import com.example.core.security.MediaPickerReturnPolicy
import org.junit.Assert.*
import org.junit.Test

class MediaPickerReturnPolicyTest {
    private val start = 10_000L

    @Test fun trustedPickerReturnSkipsOnlyRedundantPinByDefault() {
        assertTrue(MediaPickerReturnPolicy.mayResumeWithoutPin(
            requirePinOnReturn = false, pickerStartedElapsedMs = start,
            nowElapsedMs = start + 30_000L,
            isUnlocked = true, screenWasLocked = false))
    }

    @Test fun userPreferenceCanRequirePinOnEveryPickerReturn() {
        assertFalse(MediaPickerReturnPolicy.mayResumeWithoutPin(
            requirePinOnReturn = true, pickerStartedElapsedMs = start,
            nowElapsedMs = start + 30_000L,
            isUnlocked = true, screenWasLocked = false))
    }

    @Test fun expiredOrCorruptSessionCannotBypassPin() {
        for (time in listOf(-1L, start - 1, start + MediaPickerReturnPolicy.MAX_PICKER_SESSION_MS + 1)) {
            assertFalse(MediaPickerReturnPolicy.mayResumeWithoutPin(
                false, start, time, true, false))
        }
        assertFalse(MediaPickerReturnPolicy.mayResumeWithoutPin(false, null, start + 1, true, false))
    }

    @Test fun lockScreenOrPreviouslyLockedAreaNeverBypassesPin() {
        assertFalse(MediaPickerReturnPolicy.mayResumeWithoutPin(
            false, start, start + 20_000L, false, false))
        assertFalse(MediaPickerReturnPolicy.mayResumeWithoutPin(
            false, start, start + 20_000L, true, true))
    }

    @Test fun endOfGraceWindowIsInclusiveButLongerReturnRelocks() {
        assertTrue(MediaPickerReturnPolicy.mayResumeWithoutPin(
            false, start, start + MediaPickerReturnPolicy.MAX_PICKER_SESSION_MS, true, false))
        assertFalse(MediaPickerReturnPolicy.mayResumeWithoutPin(
            false, start, start + MediaPickerReturnPolicy.MAX_PICKER_SESSION_MS + 1, true, false))
    }
}
