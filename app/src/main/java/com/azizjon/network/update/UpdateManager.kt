package com.azizjon.network.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val sha256: String,
    val notes: String,
)

sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object Unavailable : UpdateCheckResult
}

class UpdateManager(private val context: Context) {
    suspend fun checkLatest(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val body = httpGet(MANIFEST_URL) ?: return@withContext UpdateCheckResult.Unavailable
        parseUpdateManifest(body, currentVersionCode())
    }

    fun currentVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    fun currentVersionLabel(): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.versionName.orEmpty().ifBlank { "Build ${currentVersionCode()}" }
            .let { if (it.startsWith("Build")) it else "v$it (${currentVersionCode()})" }
    }

    suspend fun downloadApk(info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val outputFile = File(directory, "network-app-${info.versionCode}.apk")
        val connection = open(info.apkUrl)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Update server returned HTTP $code")
            val reportedSize = connection.contentLengthLong
            if (reportedSize > 0 && reportedSize != info.apkSizeBytes) {
                throw IOException("Update download size did not match the signed release manifest")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var lastProgress = -1
            var downloaded = 0L
            connection.inputStream.use { input ->
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        if (downloaded > info.apkSizeBytes) {
                            throw IOException("Update download exceeded its declared size")
                        }
                        val progress = ((downloaded * 100) / info.apkSizeBytes).toInt().coerceAtMost(100)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            withContext(Dispatchers.Main.immediate) { onProgress(progress) }
                        }
                    }
                }
            }
            val actualHash = digest.digest().toHex()
            if (!updateIntegrityMatches(downloaded, actualHash, info.apkSizeBytes, info.sha256)) {
                throw IOException("Update download failed integrity verification")
            }
            outputFile
        } catch (failure: Exception) {
            outputFile.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private fun httpGet(url: String): String? = try {
        val connection = open(url)
        try {
            if (connection.responseCode in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "NetworkApp-Updater")
    }

    companion object {
        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/AzizjonKasimov/network-app-releases/main/version.json"
    }
}

internal fun parseUpdateManifest(body: String, currentVersionCode: Long): UpdateCheckResult {
    val json = runCatching { JSONObject(body) }.getOrNull() ?: return UpdateCheckResult.Unavailable
    val info = UpdateInfo(
        versionCode = json.optLong("versionCode", -1),
        versionName = json.optString("versionName"),
        apkUrl = json.optString("apkUrl"),
        apkSizeBytes = json.optLong("apkSizeBytes", -1),
        sha256 = json.optString("sha256").lowercase(),
        notes = json.optString("notes"),
    )
    return when {
        info.versionCode < 0 ||
            info.versionName.isBlank() ||
            !isTrustedReleaseUrl(info.apkUrl) ||
            info.apkSizeBytes !in 1..MAX_APK_SIZE_BYTES ||
            !info.sha256.matches(Regex("[0-9a-f]{64}")) -> UpdateCheckResult.Unavailable
        info.versionCode <= currentVersionCode -> UpdateCheckResult.UpToDate
        else -> UpdateCheckResult.Available(info)
    }
}

internal fun verifyDownloadedApk(
    file: File,
    expectedSizeBytes: Long,
    expectedSha256: String,
): Boolean = file.length() == expectedSizeBytes &&
    file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        updateIntegrityMatches(file.length(), digest.digest().toHex(), expectedSizeBytes, expectedSha256)
    }

private fun updateIntegrityMatches(
    actualSizeBytes: Long,
    actualSha256: String,
    expectedSizeBytes: Long,
    expectedSha256: String,
): Boolean = actualSizeBytes == expectedSizeBytes && actualSha256.equals(expectedSha256, ignoreCase = true)

private fun isTrustedReleaseUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" &&
        uri.host == "github.com" &&
        uri.port == -1 &&
        uri.userInfo == null &&
        uri.query == null &&
        uri.fragment == null &&
        uri.path.startsWith("/AzizjonKasimov/network-app-releases/releases/download/")
}.getOrDefault(false)

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private const val MAX_APK_SIZE_BYTES = 250L * 1024L * 1024L
