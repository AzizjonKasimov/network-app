package com.azizjon.network.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo that cannot be used, with a message fit to show the user. */
class PhotoException(message: String) : IOException(message)

/**
 * Turns a picked, shared, or just-taken photo into an [AttachedPhoto] small
 * enough to send.
 *
 * A phone photo is several megabytes, and the gateway sits behind a 2 MB request
 * cap, so every photo is decoded at reduced size, turned upright, and written
 * again as a JPEG under [PhotoLimits.MAX_BYTES].
 */
class PhotoPreparer(private val context: Context) {
    suspend fun prepare(uri: Uri, id: Long): AttachedPhoto = withContext(Dispatchers.Default) {
        val bitmap = flattenAlpha(decode(uri))
        AttachedPhoto(id = id, jpeg = encode(bitmap), thumbnail = thumbnail(bitmap))
    }

    /**
     * Where the camera app writes the next photo. Older captures are cleared
     * first, since a capture's file is deleted only once it has been read.
     */
    fun newCaptureUri(): Uri {
        val folder = File(context.cacheDir, CAPTURE_FOLDER).apply { mkdirs() }
        val stale = System.currentTimeMillis() - STALE_CAPTURE_MS
        folder.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.delete() }
        val file = File(folder, "capture-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Removes a camera capture once it has been read. Never touches a photo from another app. */
    fun deleteCapture(uri: Uri) {
        if (uri.authority == "${context.packageName}.fileprovider") {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    private fun decode(uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder reports the upright size and applies the rotation the
            // camera recorded, so the target is right whichever way it was held.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val (width, height) = PhotoLimits.targetSize(info.size.width, info.size.height)
                decoder.setTargetSize(width, height)
                // Software memory, so the pixels can be drawn on and compressed.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeWithBitmapFactory(uri)
        }

    /**
     * Android 8 has no ImageDecoder, so sample down on decode instead. A camera
     * photo may arrive on its side there, which Claude still reads; turning it
     * upright would take the platform EXIF reader, which has known flaws on
     * old releases, for phones this app is unlikely to meet.
     */
    private fun decodeWithBitmapFactory(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PhotoException(UNREADABLE)
        val (width, height) = PhotoLimits.targetSize(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val sampled = open(uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw PhotoException(UNREADABLE)
        return Bitmap.createScaledBitmap(sampled, width, height, true)
    }

    /** JPEG has no transparency; a transparent screenshot would otherwise turn black. */
    private fun flattenAlpha(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return flat
    }

    private fun encode(source: Bitmap): ByteArray {
        var bitmap = source
        while (true) {
            for (quality in QUALITIES) {
                val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                if (bytes.size <= PhotoLimits.MAX_BYTES) return bytes
            }
            // A dense, detailed photo: give up some size rather than legibility.
            if (bitmap.width < MIN_SIDE || bitmap.height < MIN_SIDE) throw PhotoException("That photo could not be made small enough to send.")
            bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * 0.8).toInt(), (bitmap.height * 0.8).toInt(), true)
        }
    }

    private fun thumbnail(bitmap: Bitmap): Bitmap {
        val (width, height) = PhotoLimits.targetSize(bitmap.width, bitmap.height, PhotoLimits.THUMBNAIL_SIDE, Int.MAX_VALUE)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun open(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw PhotoException(UNREADABLE)

    private companion object {
        const val CAPTURE_FOLDER = "photos"
        const val STALE_CAPTURE_MS = 10 * 60 * 1000L
        const val MIN_SIDE = 400
        const val UNREADABLE = "That photo could not be opened."
        val QUALITIES = listOf(85, 75, 65)
    }
}
