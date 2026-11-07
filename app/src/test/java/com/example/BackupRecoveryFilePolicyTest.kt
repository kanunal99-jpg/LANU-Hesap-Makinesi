package com.example

import com.example.core.messaging.BackupRecoveryFilePolicy
import com.example.core.messaging.FullDataBackup
import org.junit.Assert.*
import org.junit.Test

class BackupRecoveryFilePolicyTest {
    private val probe = "lanu-backup-probe-" + "a".repeat(32) + ".db"
    @Test fun onlyExactProbeAndKnownSidecarsAreDisposable() {
        assertTrue(BackupRecoveryFilePolicy.isProbe(probe))
        for (suffix in listOf("", "-wal", "-shm", "-journal"))
            assertTrue(BackupRecoveryFilePolicy.isProbeOrSidecar(probe + suffix))
        for (name in listOf("lanu_calculator_db", "user.lanubak", "../$probe", "$probe.bak", "$probe-wal-extra", probe.uppercase(), "lanu-backup-probe-.db")) {
            assertFalse(name, BackupRecoveryFilePolicy.isProbe(name))
            assertFalse(name, BackupRecoveryFilePolicy.isProbeOrSidecar(name))
        }
    }
    @Test fun spaceReservationIsBoundedAndRejectsForgedArchiveLengths() {
        assertTrue(BackupRecoveryFilePolicy.minimumFreeBytes(100) >= 8 * 1024 * 1024 + 800L)
        assertEquals(8L * 1024 * 1024 + 8L * (FullDataBackup.MAX_BYTES + 56L),
            BackupRecoveryFilePolicy.minimumFreeBytes(FullDataBackup.MAX_BYTES + 56L))
        for (size in listOf(-1L, 0L, 57L, FullDataBackup.MAX_BYTES + 57L, Long.MAX_VALUE))
            assertTrue(runCatching { BackupRecoveryFilePolicy.minimumFreeBytes(size) }.isFailure)
    }
}
