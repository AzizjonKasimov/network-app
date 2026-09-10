package com.azizjon.network.feedback

import android.content.Context
import android.os.Build

/**
 * The installed build, stamped onto every report.
 *
 * Without it a report read weeks later cannot say whether the fault still
 * exists or was fixed by a release in between.
 */
fun installedAppVersion(context: Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    return "${info.versionName.orEmpty().ifBlank { "unknown" }} ($code)"
}
