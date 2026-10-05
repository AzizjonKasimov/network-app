package com.azizjon.network.ai

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A photo waiting in the composer, already shrunk and re-encoded for sending.
 *
 * Only [jpeg] travels. It is written fresh from the decoded pixels, so nothing
 * from the original file comes along, including where the photo was taken.
 * Photos live in memory only: they are never saved, backed up, or replayed.
 */
class AttachedPhoto(
    val id: Long,
    val jpeg: ByteArray,
    val thumbnail: Bitmap,
)

object PhotoLimits {
    /** Most photos in one message. The gateway refuses more. */
    const val MAX_PHOTOS = 3

    /** Claude reads an image at full detail up to this long side... */
    const val MAX_LONG_SIDE = 1_568

    /** ...and up to about this many pixels. Anything larger is shrunk on arrival anyway. */
    const val MAX_PIXELS = 1_150_000

    /**
     * Ceiling per photo. Three of them in base64, plus the prompt and tool
     * definitions, stay well inside the 2 MB request cap in front of the gateway.
     */
    const val MAX_BYTES = 350 * 1024

    const val THUMBNAIL_SIDE = 240

    /** "a photo", "2 photos": how the prompt and the thread count them. */
    fun count(photos: Int): String = if (photos == 1) "a photo" else "$photos photos"

    /** The size to scale a [width] by [height] image to: never larger, ratio kept. */
    fun targetSize(
        width: Int,
        height: Int,
        maxLongSide: Int = MAX_LONG_SIDE,
        maxPixels: Int = MAX_PIXELS,
    ): Pair<Int, Int> {
        require(width > 0 && height > 0) { "An image needs a size" }
        val scale = minOf(
            1.0,
            maxLongSide.toDouble() / max(width, height),
            sqrt(maxPixels.toDouble() / (width.toLong() * height)),
        )
        // Rounding down keeps the result inside both limits.
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }
}
