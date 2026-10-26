package com.example

import com.example.core.diagnostics.PrivateRelayHealth
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PrivateRelayHealthTest {
    @After fun clear() = PrivateRelayHealth.clear()

    @Test fun onlyFixedCategoriesAppearInThePrivateSummary() {
        PrivateRelayHealth.clear()
        PrivateRelayHealth.recordHttp(200)
        PrivateRelayHealth.recordHttp(409)
        PrivateRelayHealth.recordHttp(429)
        PrivateRelayHealth.recordHttp(401)
        PrivateRelayHealth.record(PrivateRelayHealth.Outcome.NETWORK)
        PrivateRelayHealth.record(PrivateRelayHealth.Outcome.RESPONSE)
        val report = PrivateRelayHealth.safeSummary()
        assertTrue(report.contains("Başarılı: 1"))
        assertTrue(report.contains("Kayıt çakışması: 1"))
        assertTrue(report.contains("İstek sınırlama: 1"))
        assertFalse(report.contains("private"))
        assertFalse(report.contains("https://"))
        assertEquals(6, PrivateRelayHealth.snapshot().values.sum())
    }

    @Test fun unknownHttpStatusesNeverRecordResponseBodyOrIdentifiers() {
        PrivateRelayHealth.clear()
        PrivateRelayHealth.recordHttp(503)
        PrivateRelayHealth.recordHttp(418)
        assertEquals(2, PrivateRelayHealth.snapshot()
            [PrivateRelayHealth.Outcome.OTHER])
        assertFalse(PrivateRelayHealth.safeSummary().contains("503"))
        assertFalse(PrivateRelayHealth.safeSummary().contains("418"))
    }

    @Test fun countersAreThreadSafeAndSaturate() {
        PrivateRelayHealth.clear()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = (1..8).map { pool.submit {
                repeat(2400) {
                    PrivateRelayHealth.record(PrivateRelayHealth.Outcome.NETWORK)
                }
            } }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(9999, PrivateRelayHealth.snapshot()
                [PrivateRelayHealth.Outcome.NETWORK])
        } finally { pool.shutdownNow() }
    }

    @Test fun explicitClearRemovesOnlySessionCounters() {
        PrivateRelayHealth.recordHttp(200)
        PrivateRelayHealth.clear()
        assertEquals(0, PrivateRelayHealth.snapshot().values.sum())
        assertEquals("Bu oturumda sunucu bağlantısı denemesi yok.",
            PrivateRelayHealth.safeSummary())
    }
}
