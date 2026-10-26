package com.example

import com.example.core.security.StorePublicationReadiness
import com.example.core.security.StorePublicationReadiness.Decision
import com.example.core.security.StorePublicationReadiness.Evidence
import com.example.core.security.StorePublicationReadiness.Requirement
import org.junit.Assert.*
import org.junit.Test

class StorePublicationReadinessTest {
    @Test fun missingLegalOwnerAndPublicPrivacyUrlCannotApprovePublishing() {
        val decision = StorePublicationReadiness.assess(Evidence())
        assertTrue(decision is Decision.Blocked)
        val missing = (decision as Decision.Blocked).missing
        assertEquals(Requirement.values().toSet(), missing)
    }

    @Test fun insecureOrDeceptivePrivacyUrlsAlwaysBlock() {
        val urls = listOf("", "http://privacy.example/test",
            "https://localhost/policy", "https://name@privacy.example/policy",
            "https://privacy.example/policy#hidden", "not-a-url")
        for (url in urls) {
            val ready = complete().copy(privacyPolicyUrl = url)
            val decision = StorePublicationReadiness.assess(ready)
            assertTrue(decision is Decision.Blocked)
            assertTrue(Requirement.PUBLIC_HTTPS_PRIVACY_POLICY in (decision as Decision.Blocked).missing)
        }
    }

    @Test fun syntheticCompleteEvidenceStillRequiresHumanLegalReview() {
        assertEquals(Decision.HumanLegalReviewRequired,
            StorePublicationReadiness.assess(complete()))
        assertFalse(Decision.HumanLegalReviewRequired.toString().contains("Published"))
    }

    private fun complete() = Evidence(ownerVerified = true, supportVerified = true,
        privacyPolicyUrl = "https://privacy.example/policy",
        retentionAndDeletionReviewed = true, dataSafetyReviewed = true,
        permissionsReviewed = true)
}
