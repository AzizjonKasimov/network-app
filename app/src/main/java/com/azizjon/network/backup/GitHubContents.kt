package com.azizjon.network.backup

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/** A file in a repository folder listing. */
data class GitHubFile(val name: String, val path: String, val sha: String, val size: Long)

/** A file's text, with the blob sha that replacing or deleting it requires. */
data class GitHubText(val sha: String, val text: String)

/**
 * The few GitHub Contents API calls the backup and the PC inbox make.
 *
 * An interface so the inbox's rules can be tested without a network.
 */
interface GitHubFiles {
    /** Throws unless the token can see the repository and it is private. */
    fun requirePrivateRepository(config: GitHubBackupConfig)

    fun read(config: GitHubBackupConfig, path: String): GitHubText?

    /** The files directly inside [folder]; empty when the folder does not exist. */
    fun list(config: GitHubBackupConfig, folder: String): List<GitHubFile>

    /** Creates [path], or replaces it when [sha] is given, and returns the new blob sha. */
    fun write(config: GitHubBackupConfig, path: String, text: String, sha: String?, message: String): String

    fun delete(config: GitHubBackupConfig, path: String, sha: String, message: String)
}

class GitHubContents : GitHubFiles {
    override fun requirePrivateRepository(config: GitHubBackupConfig) {
        val response = request(config, "GET", repoUrl(config))
        if (response.code !in 200..299) throw GitHubBackupException(response.message)
        if (!JSONObject(response.body).optBoolean("private", false)) {
            throw GitHubBackupException("Backup repository must be private")
        }
    }

    override fun read(config: GitHubBackupConfig, path: String): GitHubText? {
        val response = request(config, "GET", contentUrl(config, path) + "?ref=${encode(config.branch)}")
        if (response.code == 404) return null
        if (response.code !in 200..299) throw GitHubBackupException(response.message)
        val json = JSONObject(response.body)
        val encoded = json.optString("content").replace("\n", "")
        return GitHubText(
            sha = json.optString("sha"),
            text = Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8),
        )
    }

    override fun list(config: GitHubBackupConfig, folder: String): List<GitHubFile> {
        val response = request(config, "GET", contentUrl(config, folder) + "?ref=${encode(config.branch)}")
        if (response.code == 404) return emptyList()
        if (response.code !in 200..299) throw GitHubBackupException(response.message)
        // A path naming a file answers with that file rather than a listing.
        val entries = runCatching { JSONArray(response.body) }.getOrNull()
            ?: throw GitHubBackupException("$folder in the data repository is a file, not a folder")
        return (0 until entries.length()).map(entries::getJSONObject)
            .filter { it.optString("type") == "file" }
            .map { GitHubFile(it.getString("name"), it.getString("path"), it.getString("sha"), it.optLong("size")) }
    }

    override fun write(config: GitHubBackupConfig, path: String, text: String, sha: String?, message: String): String {
        val body = JSONObject()
            .put("message", message)
            .put("branch", config.branch)
            .put("content", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
        if (!sha.isNullOrBlank()) body.put("sha", sha)
        val response = request(config, "PUT", contentUrl(config, path), body.toString().toByteArray(Charsets.UTF_8))
        if (response.code !in 200..299) throw GitHubBackupException(response.message)
        return JSONObject(response.body).optJSONObject("content")?.optString("sha").orEmpty()
    }

    override fun delete(config: GitHubBackupConfig, path: String, sha: String, message: String) {
        val body = JSONObject()
            .put("message", message)
            .put("branch", config.branch)
            .put("sha", sha)
        val response = request(config, "DELETE", contentUrl(config, path), body.toString().toByteArray(Charsets.UTF_8))
        // Already gone is what a delete wanted.
        if (response.code == 404) return
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

    private fun contentUrl(config: GitHubBackupConfig, path: String): String =
        repoUrl(config) + "/contents/" + path.trim().trimStart('/').split('/').joinToString("/") { encode(it) }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun parseError(text: String): String =
        runCatching { JSONObject(text).optString("message") }.getOrDefault("")

    private data class GitHubResponse(val code: Int, val body: String, val message: String)
}
