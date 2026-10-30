package com.example

import com.example.features.communication.NativeCallPhase
import com.example.features.communication.PrivateCallUiTextPolicy
import org.junit.Assert.*
import org.junit.Test

/** Synthetic hostile text only; physical TalkBack/lockscreen acceptance is separate. */
class PrivateCallUiTextPolicyTest {
    @Test fun lockedCalculatorNeverRendersPrivateTerminalDiagnostics() {
        val hostile = listOf(
            "v=0\\r\\nc=IN IP4 192.0.2.17",
            "peer@example.test / private conversation",
            "token=synthetic-secret",
            "Görüşme sonlandırıldı.\\nprivate payload",
            "Arama yanıtlanmadı. ",
            ""
        )
        for (message in hostile) {
            assertEquals("Arama kapandı.",
                PrivateCallUiTextPolicy.terminal(NativeCallPhase.ENDED, false, message))
            assertEquals("Görüşme tamamlanamadı.",
                PrivateCallUiTextPolicy.terminal(NativeCallPhase.FAILED, false, message))
        }
        // Even an approved user-facing message is not read on the public surface.
        assertEquals("Arama kapandı.",
            PrivateCallUiTextPolicy.terminal(NativeCallPhase.ENDED, false, "Arama reddedildi."))
    }

    @Test fun privateScreenShowsOnlyReviewedStaticMessages() {
        assertEquals("Karşı taraf aramayı reddetti.",
            PrivateCallUiTextPolicy.terminal(NativeCallPhase.ENDED, true, "Karşı taraf aramayı reddetti."))
        assertEquals("Arama kurulamadı. İzinleri ve bağlantıyı kontrol edip yeniden deneyin.",
            PrivateCallUiTextPolicy.terminal(NativeCallPhase.FAILED, true,
                "Arama kurulamadı. İzinleri ve bağlantıyı kontrol edip yeniden deneyin."))
        for (raw in listOf("192.0.2.17", "a=candidate:private", "SdpException: peer=123",
            "Karşı taraf aramayı reddetti.\\nextra", "http://example.test")) {
            assertEquals("Görüşme tamamlanamadı.",
                PrivateCallUiTextPolicy.terminal(NativeCallPhase.FAILED, true, raw))
        }
    }

    @Test fun idleNoticesRequirePrivateAreaAndReviewedCopy() {
        val allowed = "Görüşme için mikrofon, görüntülü arama için kamera izni gerekir."
        assertNull(PrivateCallUiTextPolicy.notice(false, allowed))
        assertNull(PrivateCallUiTextPolicy.notice(false, "private identity"))
        assertNull(PrivateCallUiTextPolicy.notice(true, null))
        assertEquals(allowed, PrivateCallUiTextPolicy.notice(true, allowed))
        assertEquals("Arama bilgisi kullanılamıyor.",
            PrivateCallUiTextPolicy.notice(true, "sdp=secret\\npeerId=123"))
    }
}
