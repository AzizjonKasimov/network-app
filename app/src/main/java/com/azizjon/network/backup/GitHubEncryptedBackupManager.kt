package com.azizjon.network.backup

import com.azizjon.network.data.NetworkRepository
import com.azizjon.network.data.NetworkSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

class GitHubBackupException(message: String) : Exception(message)

data class GitHubBackupResult(
    val people: Int,
    val interactions: Int,
    val needs: Int,
    val capabilities: Int,
)

class GitHubEncryptedBackupManager(
    private val repository: NetworkRepository,
    private val settings: GitHubBackupSettings,
) {
    suspend fun backup(config: GitHubBackupConfig): GitHubBackupResult = withContext(Dispatchers.IO) {
        requireConfigured(config)
        requirePrivateRepository(config)
        val snapshot = repository.snapshot()
        val encrypted = EncryptedBackupCodec.encode(snapshot, config.passphrase)
        val existing = getContent(config)
        putContent(config, encrypted, existing?.sha)
        settings.markBackedUp()
        GitHubBackupResult(
            people = snapshot.people.size,
            interactions = snapshot.interactions.size,
            needs = snapshot.needs.size,
            capabilities = snapshot.capabilities.size,
        )
    }

    suspend fun download(config: GitHubBackupConfig): NetworkSnapshot = withContext(Dispatchers.IO) {
        requireConfigured(config)
        requirePrivateRepository(config)
        val content = getContent(config)?.text ?: throw GitHubBackupException("GitHub backup file was not found")
        try {
            EncryptedBackupCodec.decode(content, config.passphrase)
        } catch (e: BackupCodecException) {
            throw GitHubBackupException(e.message ?: "Backup could not be decrypted")
        }
    }

    private fun requireConfigured(config: GitHubBackupConfig) {
        if (!config.configured) {
            throw GitHubBackupException(
                "Complete the GitHub settings and use a passphrase of at least ${GitHubBackupConfig.MIN_PASSPHRASE_LENGTH} characters",
            )
        }
    }

    private fun requirePrivateRepository(config: GitHubBackupConfig) {
        val response = request(config, "GET", repoUrl(config))
        if (response.code !in 200..299) throw GitHubBackupException(response.message)
        if (!JSONObject(response.body).optBoolean("private", false)) {
            throw GitHubBackupException("Backup repository must be private")
        }
    }

    private fun getContent(config: GitHubBackupConfig): GitHubContent? {
        val response = request(config, "GET", contentUrl(config) + "?ref=${encode(config.branch)}")
        if (response.code == 404) return null
        if (response.code !in 200..299) throw GitHubBackupException(response.message)
        val json = JSONObject(response.body)
        val encoded = json.optString("content").replace("\n", "")
        return GitHubContent(
            sha = json.optString("sha"),
            text = Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8),
        )
    }

    private fun putContent(config: GitHubBackupConfig, content: String, sha: String?) {
        val body = JSONObject()
            .put("message", "backup(android): ${System.currentTimeMillis()}")
            .put("branch", config.branch)
            .put("content", Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
        if (!sha.isNullOrBlank()) body.put("sha", sha)
        val response = request(config, "PUT", contentUrl(config), body.toString().toByteArray(Charsets.UTF_8))
        if (response.code !in 200..299) throw GitHubBackupException(response.message)
    }

    private fun request(
        config: GitHubBackupConfig,
        method: String,
        url: String,
        body: ByteArray? = null,
    ): GitHubResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer ${config.token}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            GitHubResponse(code, text, parseError(text).ifBlank { "GitHub HTTP $code" })
        } finally {
            connection.disconnect()
        }
    }

    private fun repoUrl(config: GitHubBackupConfig): String =
        "https://api.github.com/repos/${encode(config.owner)}/${encode(config.repo)}"

    private fun contentUrl(config: GitHubBackupConfig): String =
        repoUrl(config) + "/contents/" + config.path.trim().trimStart('/').split('/').joinToString("/") { encode(it) }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun parseError(text: String): String =
        runCatching { JSONObject(text).optString("message") }.getOrDefault("")

    private data class GitHubContent(val sha: String, val text: String)
    private data class GitHubResponse(val code: Int, val body: String, val message: String)
}
