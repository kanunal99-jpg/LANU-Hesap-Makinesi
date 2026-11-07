package com.example.core.messaging

/** Exclusive disposable namespace; never accepts a live DB or a portable archive. */
internal object BackupRecoveryFilePolicy {
    private val name = Regex("lanu-backup-probe-[a-f0-9]{32}\\.db")
    fun isProbe(name: String): Boolean = this.name.matches(name)
    fun isProbeOrSidecar(name: String): Boolean =
        isProbe(name) || listOf("-wal", "-shm", "-journal").any { suffix ->
            name.endsWith(suffix) && isProbe(name.removeSuffix(suffix))
        }
    fun minimumFreeBytes(archiveBytes: Long): Long {
        require(archiveBytes in 58L..(FullDataBackup.MAX_BYTES + 56L))
        return 8L * 1024 * 1024 + 8L * archiveBytes
    }
}
