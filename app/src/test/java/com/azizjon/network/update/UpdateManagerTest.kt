package com.azizjon.network.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateManagerTest {
    @Test
    fun newerManifestIsAvailable() {
        val result = parseUpdateManifest(
            """
            {
              "versionCode": 2,
              "versionName": "0.2.0",
              "apkUrl": "https://github.com/AzizjonKasimov/network-app-releases/releases/download/v0.2.0/NetworkApp-0.2.0.apk",
              "apkSizeBytes": 12345678,
              "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "notes": "New release"
            }
            """.trimIndent(),
            currentVersionCode = 1,
        )

        assertTrue(result is UpdateCheckResult.Available)
        assertEquals("0.2.0", (result as UpdateCheckResult.Available).info.versionName)
    }

    @Test
    fun sameVersionIsUpToDate() {
        val result = parseUpdateManifest(
            validManifest(versionCode = 1),
            currentVersionCode = 1,
        )

        assertEquals(UpdateCheckResult.UpToDate, result)
    }

    @Test
    fun insecureOrBrokenManifestIsUnavailable() {
        assertEquals(UpdateCheckResult.Unavailable, parseUpdateManifest("not json", 1))
        assertEquals(
            UpdateCheckResult.Unavailable,
            parseUpdateManifest(
                validManifest(apkUrl = "http://github.com/AzizjonKasimov/network-app-releases/releases/download/v0.2.0/app.apk"),
                1,
            ),
        )
    }

    @Test
    fun foreignHttpsHostIsUnavailable() {
        assertEquals(
            UpdateCheckResult.Unavailable,
            parseUpdateManifest(validManifest(apkUrl = "https://example.com/app.apk"), 1),
        )
    }

    @Test
    fun missingIntegrityMetadataIsUnavailable() {
        val manifest = """
            {
              "versionCode": 2,
              "versionName": "0.2.0",
              "apkUrl": "https://github.com/AzizjonKasimov/network-app-releases/releases/download/v0.2.0/app.apk"
            }
        """.trimIndent()

        assertEquals(UpdateCheckResult.Unavailable, parseUpdateManifest(manifest, 1))
    }

    @Test
    fun downloadedApkMustMatchSizeAndSha256() {
        val file = File.createTempFile("network-update", ".apk")
        try {
            file.writeText("abc")
            val expectedHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

            assertTrue(verifyDownloadedApk(file, 3, expectedHash))
            assertFalse(verifyDownloadedApk(file, 4, expectedHash))
            file.writeText("abd")
            assertFalse(verifyDownloadedApk(file, 3, expectedHash))
        } finally {
            file.delete()
        }
    }

    private fun validManifest(
        versionCode: Long = 2,
        apkUrl: String = "https://github.com/AzizjonKasimov/network-app-releases/releases/download/v0.2.0/NetworkApp-0.2.0.apk",
    ): String = """
        {
          "versionCode": $versionCode,
          "versionName": "0.2.0",
          "apkUrl": "$apkUrl",
          "apkSizeBytes": 12345678,
          "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "notes": "New release"
        }
    """.trimIndent()
}
