package com.azizjon.network.backup

import com.azizjon.network.data.NetworkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GitHubBackupException(message: String) : Exception(message)

/** What the stored backup turned out to hold, once the passphrase opened it. */
data class BackupCheck(
    val people: Int,
    val interactions: Int,
    val feedback: Int,
)

data class GitHubBackupResult(
    val people: Int,
    val interactions: Int,
    val needs: Int,
    val capabilities: Int,
    val feedback: Int,
)

class GitHubEncryptedBackupManager(
    private val repository: NetworkRepository,
    private val settings: GitHubBackupSettings,
    private val files: GitHubFiles,
) {
    suspend fun backup(config: GitHubBackupConfig): GitHubBackupResult = withContext(Dispatchers.IO) {
        requireConfigured(config)
        files.requirePrivateRepository(config)
        val payload = BackupPayload(repository.snapshot(), repository.allFeedback())
        val encrypted = EncryptedBackupCodec.encode(payload, config.passphrase)
        val existing = files.read(config, config.path)
        files.write(config, config.path, encrypted, existing?.sha, "backup(android): ${System.currentTimeMillis()}")
        settings.markBackedUp()
        GitHubBackupResult(
            people = payload.snapshot.people.size,
            interactions = payload.snapshot.interactions.size,
            needs = payload.snapshot.needs.size,
            capabilities = payload.snapshot.capabilities.size,
            feedback = payload.feedback.size,
        )
    }

    suspend fun download(config: GitHubBackupConfig): BackupPayload = withContext(Dispatchers.IO) {
        requireConfigured(config)
        files.requirePrivateRepository(config)
        val content = files.read(config, config.path)?.text ?: throw GitHubBackupException("GitHub backup file was not found")
        try {
            EncryptedBackupCodec.decode(content, config.passphrase)
        } catch (e: BackupCodecException) {
            throw GitHubBackupException(e.message ?: "Backup could not be decrypted")
        }
    }

    /**
     * Proves the saved passphrase still opens the stored backup.
     *
     * A backup that cannot be decrypted is indistinguishable from a good one
     * until the day it is needed, which is the one day there is no second copy
     * to fall back on. This reads the real stored file with the real saved
     * passphrase and throws away what it finds: nothing is written to the
     * database, to the repository, or to disk.
     */
    suspend fun verify(config: GitHubBackupConfig): BackupCheck {
        val payload = download(config)
        return BackupCheck(
            people = payload.snapshot.people.size,
            interactions = payload.snapshot.interactions.size,
            feedback = payload.feedback.size,
        )
    }

    private fun requireConfigured(config: GitHubBackupConfig) {
        if (!config.configured) {
            throw GitHubBackupException(
                "Complete the GitHub settings and use a passphrase of at least ${GitHubBackupConfig.MIN_PASSPHRASE_LENGTH} characters",
            )
        }
    }
}
