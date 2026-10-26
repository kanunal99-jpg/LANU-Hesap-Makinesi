package com.example.core.security

import java.net.URI

/**
 * Owner-controlled Play Store privacy/legal readiness gate. This is NOT a
 * legal certification, publication button, SDK telemetry or remote verifier.
 * The product has no approved policy yet; missing evidence stays blocked.
 */
object StorePublicationReadiness {
    enum class Requirement {
        OWNER_IDENTITY, SUPPORT_CONTACT, PUBLIC_HTTPS_PRIVACY_POLICY,
        RETENTION_AND_DELETION, DATA_SAFETY_DECLARATION, PERMISSIONS_REVIEW
    }
    data class Evidence(
        val ownerVerified: Boolean = false,
        val supportVerified: Boolean = false,
        val privacyPolicyUrl: String = "",
        val retentionAndDeletionReviewed: Boolean = false,
        val dataSafetyReviewed: Boolean = false,
        val permissionsReviewed: Boolean = false
    )
    sealed class Decision {
        data class Blocked(val missing: Set<Requirement>) : Decision()
        /** Requires separate human/legal approval: never automatically publish. */
        object HumanLegalReviewRequired : Decision()
    }
    private fun hasPublicHttpsPolicy(url: String): Boolean =
        runCatching {
            val uri = URI(url)
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host != null && uri.host.contains('.') &&
                uri.host != "localhost" && uri.userInfo == null &&
                uri.fragment == null && uri.rawQuery == null
        }.getOrDefault(false)

    fun assess(evidence: Evidence): Decision {
        val missing = mutableSetOf<Requirement>()
        if (!evidence.ownerVerified) missing += Requirement.OWNER_IDENTITY
        if (!evidence.supportVerified) missing += Requirement.SUPPORT_CONTACT
        if (!hasPublicHttpsPolicy(evidence.privacyPolicyUrl))
            missing += Requirement.PUBLIC_HTTPS_PRIVACY_POLICY
        if (!evidence.retentionAndDeletionReviewed)
            missing += Requirement.RETENTION_AND_DELETION
        if (!evidence.dataSafetyReviewed)
            missing += Requirement.DATA_SAFETY_DECLARATION
        if (!evidence.permissionsReviewed)
            missing += Requirement.PERMISSIONS_REVIEW
        return if (missing.isEmpty()) Decision.HumanLegalReviewRequired
            else Decision.Blocked(missing)
    }
}
