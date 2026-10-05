package com.azizjon.network.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoLimitsTest {
    @Test
    fun aPhoneCameraPhotoShrinksToWhatClaudeReadsAtFullDetail() {
        val (width, height) = PhotoLimits.targetSize(4000, 3000)

        assertTrue(width <= PhotoLimits.MAX_LONG_SIDE)
        assertTrue(width.toLong() * height <= PhotoLimits.MAX_PIXELS)
        // The shape is kept.
        assertEquals(4.0 / 3.0, width.toDouble() / height, 0.01)
    }

    @Test
    fun aTallScreenshotIsLimitedByItsLongSide() {
        val (width, height) = PhotoLimits.targetSize(1080, 2400)

        assertTrue(height <= PhotoLimits.MAX_LONG_SIDE)
        assertTrue(width.toLong() * height <= PhotoLimits.MAX_PIXELS)
        assertEquals(1080.0 / 2400.0, width.toDouble() / height, 0.01)
    }

    @Test
    fun aSmallPhotoIsNeverEnlarged() {
        assertEquals(640 to 480, PhotoLimits.targetSize(640, 480))
    }

    @Test
    fun aThumbnailIgnoresThePixelBudget() {
        assertEquals(240 to 180, PhotoLimits.targetSize(4000, 3000, maxLongSide = 240, maxPixels = Int.MAX_VALUE))
    }

    @Test
    fun threePhotosAtTheirCeilingStillFitTheGatewaysRequestCap() {
        val base64 = PhotoLimits.MAX_PHOTOS * ((PhotoLimits.MAX_BYTES + 2) / 3) * 4
        // Leaves room for the prompt, the tool definitions, and the message under 2 MB.
        assertTrue(base64 < 1_600_000)
    }

    @Test
    fun countsReadNaturally() {
        assertEquals("a photo", PhotoLimits.count(1))
        assertEquals("3 photos", PhotoLimits.count(3))
    }
}
