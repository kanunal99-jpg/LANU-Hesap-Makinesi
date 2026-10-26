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
            "https://privacy.example.com/policy#hidden", "not-a-url",
            "https://127.0.0.1/policy", "https://192.168.1.22/privacy",
            "https://[::1]/privacy", "https://intranet.local/policy",
            "https://privacy.internal/policy", "https://private.home.arpa/policy",
            "https://privacy.example.com/policy", "https://policy.test/privacy",
            "https://foo.invalid/policy", "https://one.localhost/privacy",
            "https://privacy.example.com:8080/policy",
            "https://privacy.example.com/policy?q=token",
            "https://foo..com/policy", "https://-bad.com/policy")
        for (url in urls) {
            val ready = complete().copy(privacyPolicyUrl = url)
            val decision = StorePublicationReadiness.assess(ready)
            assertTrue(decision is Decision.Blocked)
            assertTrue(Requirement.PUBLIC_HTTPS_PRIVACY_POLICY in (decision as Decision.Blocked).missing)
        }
    }

    @Test fun standardPublicHttpsDomainsAndPunycodeAreEligibleForHumanReview() {
        for (url in listOf(
            "https://privacy.example.com/policy",
            "https://privacy.example.com:443/policy",
            "https://xn--bcher-kva.de/datenschutz"
        )) {
            assertEquals(Decision.HumanLegalReviewRequired,
                StorePublicationReadiness.assess(complete().copy(privacyPolicyUrl = url)))
        }
    }

    @Test fun safeUrlShapeAloneNeverMeansPolicyIsActuallyPublished() {
        assertEquals(Decision.HumanLegalReviewRequired,
            StorePublicationReadiness.assess(complete()))
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
