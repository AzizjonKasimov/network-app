package com.azizjon.network.ui

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.azizjon.network.R
import com.azizjon.network.ai.AttachedPhoto
import com.azizjon.network.ai.PhotoLimits

/**
 * The composer's photo button: pick from the gallery or take one with the camera.
 *
 * Neither needs a permission. The system photo picker hands over only what was
 * chosen, and the camera app writes into a file this app offers it.
 */
@Composable
internal fun PhotoButton(
    enabled: Boolean,
    onPicked: (List<Uri>) -> Unit,
    onCaptured: (Uri) -> Unit,
    newCaptureUri: () -> Uri,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    // Survives the activity being recreated while the camera app is in front.
    var pendingCapture by rememberSaveable { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PhotoLimits.MAX_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCapture
        pendingCapture = null
        // A cancelled capture leaves an empty file, which the next capture clears.
        if (saved && uri != null) onCaptured(uri)
    }

    Box {
        IconButton(onClick = { menuOpen = true }, enabled = enabled) {
            Icon(
                painter = painterResource(R.drawable.ic_photo),
                contentDescription = "Add a photo",
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Choose photos") },
                onClick = {
                    menuOpen = false
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            DropdownMenuItem(
                text = { Text("Take a photo") },
                onClick = {
                    menuOpen = false
                    val uri = newCaptureUri()
                    pendingCapture = uri
                    try {
                        capture.launch(uri)
                    } catch (missing: ActivityNotFoundException) {
                        pendingCapture = null
                        Toast.makeText(context, "No camera app was found.", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }
}

/** Photos waiting in the composer, each with a way to take it back out. */
@Composable
internal fun AttachedPhotosRow(
    photos: List<AttachedPhoto>,
    preparing: Int,
    enabled: Boolean,
    onRemove: (Long) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        photos.forEach { photo ->
            Box {
                PhotoThumbnail(photo.thumbnail, 64)
                Surface(
                    onClick = { onRemove(photo.id) },
                    enabled = enabled,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp)
                        .semantics { contentDescription = "Remove this photo" },
                ) {
                    Box(contentAlignment = Alignment.Center) { Text("×") }
                }
            }
        }
        repeat(preparing) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/** The photos a sent message carried, shown above its text. */
@Composable
internal fun SentPhotosRow(thumbnails: List<Bitmap>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        thumbnails.forEach { PhotoThumbnail(it, 96) }
    }
}

@Composable
private fun PhotoThumbnail(bitmap: Bitmap, sizeDp: Int) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = "Photo",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(MaterialTheme.shapes.small),
    )
}
