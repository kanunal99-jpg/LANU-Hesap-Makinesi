package com.example.core.database

/**
 * A single independent acceptance gate for an encrypted SQLCipher shadow
 * produced from a frozen Room v4 source. No live database is opened, renamed
 * or deleted here. A "passed" result authorizes ONLY an offline comparison:
 * production cutover still requires the user's physical-device acceptance
 * and a separately reviewed atomic rollback implementation.
 *
 * Prevents treating equal row counts as equivalent when schema, typed values,
 * WAL frames, source signer, encrypted reopen or backup verification differ.
 */
internal object SqlCipherShadowAcceptance {
    enum class Failure {
        UNSAFE_CUTOVER_GATE, MISSING_TABLE_OR_ROW, MISMATCHED_SCHEMA,
        MISMATCHED_TYPED_ROWS, SOURCE_MUTATED, ENCRYPTED_SHADOW_INVALID,
        INVALID_CRYPTOGRAPHIC_EVIDENCE
    }
    sealed class Result {
        data class Rejected(val failure: Failure) : Result()
        object OfflineShadowVerifiedOnly : Result()
    }

    data class Evidence(
        val cutoverDecision: DatabaseCipherCutoverGate.Decision,
        val sourceSchema: List<OfflineCipherSchemaGate.SchemaProof>,
        val encryptedSchema: List<OfflineCipherSchemaGate.SchemaProof>,
        val sourceRows: List<DatabaseShadowParity.TableProof>,
        val encryptedRows: List<DatabaseShadowParity.TableProof>,
        val sourceShaBefore: String,
        val sourceShaAfter: String,
        val shadowHeaderIsEncrypted: Boolean,
        val cipherIntegrityVerified: Boolean
    )

    fun assess(value: Evidence): Result {
        if (value.cutoverDecision !is DatabaseCipherCutoverGate.Decision.ShadowTestOnly)
            return Result.Rejected(Failure.UNSAFE_CUTOVER_GATE)
        val validDigest = Regex("[a-f0-9]{64}")
        if (!validDigest.matches(value.sourceShaBefore) ||
            !validDigest.matches(value.sourceShaAfter))
            return Result.Rejected(Failure.INVALID_CRYPTOGRAPHIC_EVIDENCE)
        if (value.sourceShaBefore != value.sourceShaAfter)
            return Result.Rejected(Failure.SOURCE_MUTATED)
        if (!value.shadowHeaderIsEncrypted || !value.cipherIntegrityVerified)
            return Result.Rejected(Failure.ENCRYPTED_SHADOW_INVALID)
        if (value.sourceSchema.size != DatabaseShadowParity.TABLES.size ||
            value.encryptedSchema.size != DatabaseShadowParity.TABLES.size ||
            value.sourceRows.size != DatabaseShadowParity.TABLES.size ||
            value.encryptedRows.size != DatabaseShadowParity.TABLES.size)
            return Result.Rejected(Failure.MISSING_TABLE_OR_ROW)
        if (!OfflineCipherSchemaGate.equivalent(value.sourceSchema, value.encryptedSchema))
            return Result.Rejected(Failure.MISMATCHED_SCHEMA)
        if (!DatabaseShadowParity.equivalent(value.sourceRows, value.encryptedRows))
            return Result.Rejected(Failure.MISMATCHED_TYPED_ROWS)
        return Result.OfflineShadowVerifiedOnly
    }
}
