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
            "https://privacy.example/policy", "https://policy.test/privacy",
            "https://foo.invalid/policy", "https://one.localhost/privacy",
            "https://privacy.example.com:8080/policy",
            "https://privacy.example.com/policy?q=token",
            "https://foo..com/policy", "https://-bad.com/policy",
            "https://privacy.example.com/policy#fragment",
            "https://user:pass@privacy.example.com/policy")
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

    @Test fun everyCurrentDisclosureIsIndependentlyRequired() {
        val cases = listOf(
            complete().copy(optionalFcmCallAlertsReviewed = false) to Requirement.OPTIONAL_FCM_CALL_ALERTS,
            complete().copy(audioVideoMediaDisclosureReviewed = false) to Requirement.AUDIO_VIDEO_MEDIA_DISCLOSURE,
            complete().copy(databaseEncryptionClaimsReviewed = false) to Requirement.DATABASE_ENCRYPTION_CLAIMS,
            complete().copy(ratchetClaimsReviewed = false) to Requirement.RATCHET_CLAIMS
        )
        cases.forEach { (evidence, requirement) ->
            val decision = StorePublicationReadiness.assess(evidence)
            assertTrue(decision is Decision.Blocked)
            assertTrue(requirement in (decision as Decision.Blocked).missing)
        }
    }

    @Test fun missingRemoteAccountDeletionImplementationMustBlockStore() {
        // Local chat hiding or a reviewed retention paragraph is not remote
        // identity/ciphertext deletion. Do not treat them as equivalent.
        val decision = StorePublicationReadiness.assess(
            complete().copy(accountDeletionFlowVerified = false))
        assertTrue(decision is Decision.Blocked)
        assertTrue(Requirement.ACCOUNT_DELETION_FLOW in (decision as Decision.Blocked).missing)
    }

    @Test fun accountDeletionRequestCannotPointToLocalPrivateOrNonHttpsSite() {
        for (invalid in listOf("", "http://privacy.example.com/delete",
            "https://127.0.0.1/delete", "https://localhost/delete",
            "https://private.home.arpa/delete", "https://privacy.example.com:8080/delete",
            "https://privacy.example.com/delete?token=secret")) {
            val decision = StorePublicationReadiness.assess(
                complete().copy(accountDeletionRequestUrl = invalid))
            assertTrue(decision is Decision.Blocked)
            assertTrue(Requirement.PUBLIC_ACCOUNT_DELETION_REQUEST in
                (decision as Decision.Blocked).missing)
        }
    }

    @Test fun syntheticCompleteEvidenceStillRequiresHumanLegalReview() {
        assertEquals(Decision.HumanLegalReviewRequired,
            StorePublicationReadiness.assess(complete()))
        assertFalse(Decision.HumanLegalReviewRequired.toString().contains("Published"))
    }

    private fun complete() = Evidence(ownerVerified = true, supportVerified = true,
        privacyPolicyUrl = "https://privacy.example.com/policy",
        retentionAndDeletionReviewed = true, dataSafetyReviewed = true,
        permissionsReviewed = true, optionalFcmCallAlertsReviewed = true,
        audioVideoMediaDisclosureReviewed = true,
        databaseEncryptionClaimsReviewed = true, ratchetClaimsReviewed = true,
        accountDeletionFlowVerified = true,
        accountDeletionRequestUrl = "https://privacy.example.com/delete-account")
}
