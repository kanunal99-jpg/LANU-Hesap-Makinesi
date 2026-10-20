package com.example

import com.example.core.messaging.IdentityBackup
import com.example.core.messaging.UserCodes
import org.junit.Assert.*
import org.junit.Test

class IdentityBackupValidationTest {
    @Test fun `healthy identity backup is usable after explicit password decryption`() {
        val identity = UserCodes.create()
        val password = "backup-password-2026-safely"
        val backup = IdentityBackup.export(identity, password)
        assertFalse("Backup must not expose private key", backup.contains(identity.privateKey))
        assertEquals(identity, IdentityBackup.restore(backup, password))
    }

    @Test fun `mismatched private key must never produce misleading encrypted backup`() {
        val account = UserCodes.create()
        val different = UserCodes.create()
        for (bad in listOf(
            account.copy(privateKey = different.privateKey),
            account.copy(privateKey = "not-an-ec-private-key"),
            account.copy(code = different.code),
            account.copy(id = different.id)
        )) {
            try {
                IdentityBackup.export(bad, "safe-backup-password-2026")
                fail("Unrestorable account backup must be rejected at export time")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
