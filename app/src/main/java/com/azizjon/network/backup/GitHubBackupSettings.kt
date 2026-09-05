package com.azizjon.network.backup

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class GitHubBackupConfig(
    val owner: String = GitHubBackupSettings.DEFAULT_OWNER,
    val repo: String = GitHubBackupSettings.DEFAULT_REPO,
    val branch: String = GitHubBackupSettings.DEFAULT_BRANCH,
    val path: String = GitHubBackupSettings.DEFAULT_PATH,
    val token: String = "",
    val passphrase: String = "",
    val autoBackup: Boolean = true,
) {
    val configured: Boolean
        get() = owner.isNotBlank() && repo.isNotBlank() && branch.isNotBlank() && path.isNotBlank() &&
            token.isNotBlank() && passphrase.length >= MIN_PASSPHRASE_LENGTH

    companion object {
        const val MIN_PASSPHRASE_LENGTH = 12
    }
}

data class BackupStatus(
    val lastBackupAt: Long = 0,
    val lastAttemptAt: Long = 0,
    val lastError: String = "",
    val backupNeeded: Boolean = false,
)

class GitHubBackupSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secrets = EncryptedSharedPreferences.create(
        context,
        SECRET_PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    val config: GitHubBackupConfig
        get() = GitHubBackupConfig(
            owner = prefs.getString(KEY_OWNER, DEFAULT_OWNER).orEmpty().ifBlank { DEFAULT_OWNER },
            repo = prefs.getString(KEY_REPO, DEFAULT_REPO).orEmpty().ifBlank { DEFAULT_REPO },
            branch = prefs.getString(KEY_BRANCH, DEFAULT_BRANCH).orEmpty().ifBlank { DEFAULT_BRANCH },
            path = prefs.getString(KEY_PATH, DEFAULT_PATH).orEmpty().ifBlank { DEFAULT_PATH },
            token = secrets.getString(KEY_TOKEN, "").orEmpty(),
            passphrase = secrets.getString(KEY_PASSPHRASE, "").orEmpty(),
            autoBackup = prefs.getBoolean(KEY_AUTO_BACKUP, true),
        )

    val status: BackupStatus
        get() = BackupStatus(
            lastBackupAt = prefs.getLong(KEY_LAST_BACKUP_AT, 0L),
            lastAttemptAt = prefs.getLong(KEY_LAST_BACKUP_ATTEMPT_AT, 0L),
            lastError = prefs.getString(KEY_LAST_BACKUP_ERROR, "").orEmpty(),
            backupNeeded = prefs.getBoolean(KEY_BACKUP_NEEDED, false),
        )

    fun save(config: GitHubBackupConfig) {
        prefs.edit()
            .putString(KEY_OWNER, config.owner.trim())
            .putString(KEY_REPO, config.repo.trim())
            .putString(KEY_BRANCH, config.branch.trim())
            .putString(KEY_PATH, config.path.trim().trimStart('/'))
            .putBoolean(KEY_AUTO_BACKUP, config.autoBackup)
            .apply()
        secrets.edit()
            .putString(KEY_TOKEN, config.token.trim())
            .putString(KEY_PASSPHRASE, config.passphrase)
            .apply()
    }

    fun markBackupNeeded() {
        prefs.edit().putBoolean(KEY_BACKUP_NEEDED, true).apply()
    }

    fun markAttemptStarted() {
        prefs.edit().putLong(KEY_LAST_BACKUP_ATTEMPT_AT, System.currentTimeMillis()).apply()
    }

    fun markBackedUp() {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_LAST_BACKUP_AT, now)
            .putLong(KEY_LAST_BACKUP_ATTEMPT_AT, now)
            .putString(KEY_LAST_BACKUP_ERROR, "")
            .putBoolean(KEY_BACKUP_NEEDED, false)
            .apply()
    }

    fun markFailed(message: String) {
        prefs.edit()
            .putLong(KEY_LAST_BACKUP_ATTEMPT_AT, System.currentTimeMillis())
            .putString(KEY_LAST_BACKUP_ERROR, message.trim().take(300))
            .putBoolean(KEY_BACKUP_NEEDED, true)
            .apply()
    }

    companion object {
        const val DEFAULT_OWNER = "AzizjonKasimov"
        const val DEFAULT_REPO = "network-app-data"
        const val DEFAULT_BRANCH = "main"
        const val DEFAULT_PATH = "network-backup.enc.json"

        private const val PREFS_NAME = "github_backup"
        const val SECRET_PREFS_NAME = "github_backup_secrets"
        private const val KEY_OWNER = "owner"
        private const val KEY_REPO = "repo"
        private const val KEY_BRANCH = "branch"
        private const val KEY_PATH = "path"
        private const val KEY_TOKEN = "token"
        private const val KEY_PASSPHRASE = "passphrase"
        private const val KEY_AUTO_BACKUP = "auto_backup"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"
        private const val KEY_LAST_BACKUP_ATTEMPT_AT = "last_backup_attempt_at"
        private const val KEY_LAST_BACKUP_ERROR = "last_backup_error"
        private const val KEY_BACKUP_NEEDED = "backup_needed"
    }
}
