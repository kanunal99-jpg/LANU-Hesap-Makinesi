package com.example.core.security

import java.net.URI
import java.util.Locale

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
    /**
     * A local/private host is never a public privacy-policy page. This gate
     * validates the URL shape only; DNS reachability, policy contents and legal
     * identity still require independent owner/human verification.
     */
    internal fun hasPublicHttpsPolicy(url: String): Boolean =
        runCatching {
            if (url.length !in 12..2048 || url.any { it.isWhitespace() || it.isISOControl() }) {
                return@runCatching false
            }
            val uri = URI(url)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching false
            val labels = host.split('.')
            val reserved = setOf("localhost", "local", "lan", "internal",
                "test", "example", "invalid", "onion", "arpa", "localdomain")
            // Domain labels must be valid ASCII/punycode DNS names; this
            // rejects IPv4 literals, bracketed IPv6, localhost and suffixes
            // that cannot establish an ordinary public HTTPS policy site.
            val dnsLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
            val validPublicDnsName = host.length <= 253 &&
                labels.size >= 2 &&
                labels.all { dnsLabel.matches(it) } &&
                labels.last().length >= 2 &&
                labels.last().all { it in 'a'..'z' } &&
                labels.last() !in reserved &&
                labels.none { it == "localhost" } &&
                !(labels.size >= 2 && labels.takeLast(2).joinToString(".") == "home.arpa")
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.rawAuthority != null &&
                validPublicDnsName && uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443) &&
                uri.fragment == null && uri.rawQuery == null &&
                uri.rawPath?.startsWith('/') != false
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
