package com.azizjon.network.feedback

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes a report to a shareable file and hands it to the system share sheet.
 *
 * The file lands in the cache directory behind the existing FileProvider, so
 * nothing readable by other apps is ever left on shared storage. Each export
 * clears the ones before it: these files quote conversations, and an old copy
 * sitting in the cache is a liability with no remaining use.
 */
class FeedbackExporter(private val context: Context) {
    fun write(json: String, fileName: String): File {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        return File(directory, fileName).apply { writeText(json) }
    }

    /** Opens the system chooser so the user picks where the report goes. */
    fun share(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, "Share assistant feedback")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Drops every exported copy, for the Settings "clear" action. */
    fun clearExports() {
        File(context.cacheDir, DIRECTORY).listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val DIRECTORY = "feedback"
        const val MIME_TYPE = "application/json"
    }
}

/**
 * The installed build, stamped onto every report.
 *
 * Without it a report read weeks later cannot say whether the fault still
 * exists or was fixed by a release in between.
 */
fun installedAppVersion(context: Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    return "${info.versionName.orEmpty().ifBlank { "unknown" }} ($code)"
}
