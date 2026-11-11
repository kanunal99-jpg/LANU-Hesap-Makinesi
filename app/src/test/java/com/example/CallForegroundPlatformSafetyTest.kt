package com.example

import com.example.core.calls.CallForegroundPlatformSafety
import org.junit.Assert.*
import org.junit.Test

class CallForegroundPlatformSafetyTest {
    @Test fun platformSuccessMeansForegroundOwnershipWasAttempted() {
        var started = 0
        assertTrue(CallForegroundPlatformSafety.execute { started++ })
        assertEquals(1, started)
    }

    @Test fun permissionOemAndBackgroundRestrictionsNeverClaimForegroundStarted() {
        for (problem in listOf<Throwable>(
            SecurityException("permission revoked"),
            IllegalStateException("background start forbidden"),
            IllegalArgumentException("unsupported FGS type"),
            NoSuchMethodError("OEM framework missing"),
            UnsatisfiedLinkError("vendor native mismatch")
        )) {
            assertFalse(problem.javaClass.simpleName,
                CallForegroundPlatformSafety.execute { throw problem })
        }
    }

    @Test fun fatalVmErrorIsNotMisrepresentedAsNormalPlatformFailure() {
        val fatal = OutOfMemoryError("synthetic only")
        assertSame(fatal, runCatching {
            CallForegroundPlatformSafety.execute { throw fatal }
        }.exceptionOrNull())
    }
}
