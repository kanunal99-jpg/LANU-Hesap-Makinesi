package com.example

import com.example.core.database.DatabaseCipherCutoverGate
import com.example.core.database.DatabaseShadowParity
import com.example.core.database.OfflineCipherSchemaGate
import com.example.core.database.SqlCipherShadowAcceptance
import org.junit.Assert.assertEquals
import org.junit.Test

class SqlCipherShadowAcceptanceTest {
    private val sha = "a".repeat(64)
    private val tableNames = DatabaseShadowParity.TABLES.sorted()
    private val schema = tableNames.map {
        OfflineCipherSchemaGate.SchemaProof(it, "b".repeat(64))
    }
    private val rows = tableNames.map {
        DatabaseShadowParity.TableProof(it, 1, "c".repeat(64))
    }
    private fun evidence() = SqlCipherShadowAcceptance.Evidence(
        cutoverDecision = DatabaseCipherCutoverGate.Decision.ShadowTestOnly,
        sourceSchema = schema, encryptedSchema = schema,
        sourceRows = rows, encryptedRows = rows,
        sourceShaBefore = sha, sourceShaAfter = sha,
        shadowHeaderIsEncrypted = true, cipherIntegrityVerified = true
    )
    @Test fun identicalOfflineShadowIsNotProductionMigrationPermission() {
        assertEquals(SqlCipherShadowAcceptance.Result.OfflineShadowVerifiedOnly,
            SqlCipherShadowAcceptance.assess(evidence()))
    }
    @Test fun sourceTamperingBlocksShadowApprovalEvenWhenRowDigestsMatch() {
        assertEquals(SqlCipherShadowAcceptance.Result.Rejected(
            SqlCipherShadowAcceptance.Failure.SOURCE_MUTATED),
            SqlCipherShadowAcceptance.assess(evidence().copy(
                sourceShaAfter = "d".repeat(64))))
    }
    @Test fun encryptedIntegrityAndSchemaAreIndependentlyRequired() {
        assertEquals(SqlCipherShadowAcceptance.Result.Rejected(
            SqlCipherShadowAcceptance.Failure.ENCRYPTED_SHADOW_INVALID),
            SqlCipherShadowAcceptance.assess(evidence().copy(cipherIntegrityVerified = false)))
        assertEquals(SqlCipherShadowAcceptance.Result.Rejected(
            SqlCipherShadowAcceptance.Failure.MISMATCHED_SCHEMA),
            SqlCipherShadowAcceptance.assess(evidence().copy(
                encryptedSchema = schema.mapIndexed { index, s ->
                    if (index == 0) s.copy(sha256 = "d".repeat(64)) else s
                })))
    }
    @Test fun typedRowsMustMatchAndAllSevenTablesMustBePresent() {
        assertEquals(SqlCipherShadowAcceptance.Result.Rejected(
            SqlCipherShadowAcceptance.Failure.MISMATCHED_TYPED_ROWS),
            SqlCipherShadowAcceptance.assess(evidence().copy(
                encryptedRows = rows.mapIndexed { index, p ->
                    if (index == 0) p.copy(count = 2) else p
                })))
        assertEquals(SqlCipherShadowAcceptance.Result.Rejected(
            SqlCipherShadowAcceptance.Failure.MISSING_TABLE_OR_ROW),
            SqlCipherShadowAcceptance.assess(evidence().copy(
                encryptedRows = rows.dropLast(1))))
    }
    @Test fun cipherGuardRefusalCannotBeBypassedByMatchingFakeEvidence() {
        assertEquals(SqlCipherShadowAcceptance.Result.Rejected(
            SqlCipherShadowAcceptance.Failure.UNSAFE_CUTOVER_GATE),
            SqlCipherShadowAcceptance.assess(evidence().copy(
                cutoverDecision = DatabaseCipherCutoverGate.Decision.Blocked(
                    DatabaseCipherCutoverGate.BlockReason.BACKUP_NOT_RESTORED))))
        assertEquals(SqlCipherShadowAcceptance.Result.Rejected(
            SqlCipherShadowAcceptance.Failure.INVALID_CRYPTOGRAPHIC_EVIDENCE),
            SqlCipherShadowAcceptance.assess(evidence().copy(
                sourceShaBefore = "not-a-real-sha")))
    }
}
