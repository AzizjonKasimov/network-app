package com.azizjon.network.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.azizjon.network.R
import java.util.Locale

internal enum class SpeechInputPhase {
    IDLE,
    LISTENING,
    PROCESSING,
}

internal enum class SpeechRecognizerMode {
    ON_DEVICE,
    SYSTEM_FALLBACK,
    NEEDS_FALLBACK_DISCLOSURE,
    UNAVAILABLE,
}

internal sealed interface TranscriptAppendResult {
    data class Success(val value: String) : TranscriptAppendResult
    data object Empty : TranscriptAppendResult
    data object TooLong : TranscriptAppendResult
}

internal fun chooseSpeechRecognizerMode(
    sdkInt: Int,
    onDeviceAvailable: Boolean,
    systemRecognizerAvailable: Boolean,
    fallbackAllowed: Boolean,
): SpeechRecognizerMode = when {
    sdkInt >= Build.VERSION_CODES.S && onDeviceAvailable -> SpeechRecognizerMode.ON_DEVICE
    !systemRecognizerAvailable -> SpeechRecognizerMode.UNAVAILABLE
    fallbackAllowed -> SpeechRecognizerMode.SYSTEM_FALLBACK
    else -> SpeechRecognizerMode.NEEDS_FALLBACK_DISCLOSURE
}

internal fun appendTranscript(
    existing: String,
    transcript: String,
    maxCharacters: Int,
): TranscriptAppendResult {
    val cleanTranscript = transcript.trim().replace(Regex("\\s+"), " ")
    if (cleanTranscript.isEmpty()) return TranscriptAppendResult.Empty
    val cleanExisting = existing.trimEnd()
    val combined = if (cleanExisting.isBlank()) cleanTranscript else "$cleanExisting $cleanTranscript"
    return if (combined.length <= maxCharacters) {
        TranscriptAppendResult.Success(combined)
    } else {
        TranscriptAppendResult.TooLong
    }
}

internal fun speechErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_AUDIO -> "The microphone could not record audio. Try again."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for voice input."
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
    -> "Speech recognition is unavailable for this language."
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    SpeechRecognizer.ERROR_SERVER,
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
    -> "The speech service could not be reached. Check the connection or try on-device recognition."
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    -> "No speech was recognized. Try again and speak clearly."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Wait a moment and try again."
    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many speech requests were made. Wait a moment and try again."
    else -> "Speech recognition stopped unexpectedly. Try again."
}

@Composable
internal fun VoiceInputControl(
    value: String,
    maxCharacters: Int,
    enabled: Boolean,
    fallbackAllowed: Boolean,
    onAllowFallback: () -> Unit,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentValue = rememberUpdatedState(value)
    val currentOnValueChange = rememberUpdatedState(onValueChange)
    var localError by remember { mutableStateOf<String?>(null) }
    var showFallbackDisclosure by remember { mutableStateOf(false) }
    val controller = remember(context) {
        AndroidSpeechInputController(context.applicationContext) { transcript ->
            when (val result = appendTranscript(currentValue.value, transcript, maxCharacters)) {
                is TranscriptAppendResult.Success -> {
                    localError = null
                    currentOnValueChange.value(result.value)
                }
                TranscriptAppendResult.Empty -> localError = "No speech was recognized. Try again and speak clearly."
                TranscriptAppendResult.TooLong -> localError =
                    "The transcript would exceed $maxCharacters characters. Shorten the existing text and try again."
            }
        }
    }

    DisposableEffect(controller) {
        onDispose(controller::close)
    }
    LaunchedEffect(enabled) {
        if (!enabled) controller.cancel()
    }

    fun startWithAvailableRecognizer() {
        localError = null
        val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val mode = chooseSpeechRecognizerMode(
            sdkInt = Build.VERSION.SDK_INT,
            onDeviceAvailable = onDeviceAvailable,
            systemRecognizerAvailable = SpeechRecognizer.isRecognitionAvailable(context),
            fallbackAllowed = fallbackAllowed,
        )
        when (mode) {
            SpeechRecognizerMode.ON_DEVICE -> controller.start(onDevice = true)
            SpeechRecognizerMode.SYSTEM_FALLBACK -> controller.start(onDevice = false)
            SpeechRecognizerMode.NEEDS_FALLBACK_DISCLOSURE -> showFallbackDisclosure = true
            SpeechRecognizerMode.UNAVAILABLE -> localError = "No speech recognition service is available on this device."
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startWithAvailableRecognizer()
        else localError = "Microphone permission was denied. Voice input remains off."
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            enabled = enabled && controller.phase != SpeechInputPhase.PROCESSING,
            onClick = {
                if (controller.phase == SpeechInputPhase.LISTENING) {
                    controller.stop()
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startWithAvailableRecognizer()
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        ) {
            Icon(
                painter = painterResource(
                    if (controller.phase == SpeechInputPhase.IDLE) R.drawable.ic_mic else R.drawable.ic_stop,
                ),
                contentDescription = when (controller.phase) {
                    SpeechInputPhase.IDLE -> "Start voice input"
                    SpeechInputPhase.LISTENING -> "Stop voice input"
                    SpeechInputPhase.PROCESSING -> "Processing voice input"
                },
                tint = if (controller.phase == SpeechInputPhase.LISTENING) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        val status = when (controller.phase) {
            SpeechInputPhase.IDLE -> localError ?: controller.errorMessage ?: "Tap the microphone to dictate."
            SpeechInputPhase.LISTENING -> controller.partialTranscript
                ?.takeIf(String::isNotBlank)
                ?.let { "Listening: $it" }
                ?: "Listening… Tap stop when finished."
            SpeechInputPhase.PROCESSING -> "Processing speech…"
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = if (controller.phase == SpeechInputPhase.IDLE && (localError != null || controller.errorMessage != null)) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }

    if (showFallbackDisclosure) {
        AlertDialog(
            onDismissRequest = { showFallbackDisclosure = false },
            title = { Text("Use the phone's speech service?") },
            text = {
                Text(
                    "On-device speech recognition is unavailable. The phone's speech provider may send audio to its servers. Network App does not store the audio; only the editable transcript is added to this field. Allow this fallback until the app restarts?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showFallbackDisclosure = false
                    onAllowFallback()
                    controller.start(onDevice = false)
                }) { Text("Allow this session") }
            },
            dismissButton = {
                TextButton(onClick = { showFallbackDisclosure = false }) { Text("Cancel") }
            },
        )
    }
}

private class AndroidSpeechInputController(
    private val context: Context,
    private val onTranscript: (String) -> Unit,
) : RecognitionListener {
    var phase by mutableStateOf(SpeechInputPhase.IDLE)
        private set
    var partialTranscript by mutableStateOf<String?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var recognizer: SpeechRecognizer? = null

    fun start(onDevice: Boolean) {
        releaseRecognizer(cancelFirst = true)
        errorMessage = null
        partialTranscript = null
        try {
            recognizer = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }.also { service ->
                service.setRecognitionListener(this)
                phase = SpeechInputPhase.LISTENING
                service.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        if (onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    },
                )
            }
        } catch (error: SecurityException) {
            phase = SpeechInputPhase.IDLE
            errorMessage = "Microphone permission is required for voice input."
            releaseRecognizer(cancelFirst = false)
        } catch (error: RuntimeException) {
            phase = SpeechInputPhase.IDLE
            errorMessage = "Speech recognition could not start on this device."
            releaseRecognizer(cancelFirst = false)
        }
    }

    fun stop() {
        if (phase != SpeechInputPhase.LISTENING) return
        phase = SpeechInputPhase.PROCESSING
        recognizer?.stopListening()
    }

    fun cancel() {
        phase = SpeechInputPhase.IDLE
        partialTranscript = null
        releaseRecognizer(cancelFirst = true)
    }

    fun close() {
        cancel()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        phase = SpeechInputPhase.LISTENING
    }

    override fun onBeginningOfSpeech() {
        phase = SpeechInputPhase.LISTENING
    }

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        phase = SpeechInputPhase.PROCESSING
    }

    override fun onError(error: Int) {
        phase = SpeechInputPhase.IDLE
        partialTranscript = null
        errorMessage = speechErrorMessage(error)
        releaseRecognizer(cancelFirst = false)
    }

    override fun onResults(results: Bundle?) {
        val transcript = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        phase = SpeechInputPhase.IDLE
        partialTranscript = null
        errorMessage = null
        releaseRecognizer(cancelFirst = false)
        onTranscript(transcript)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialTranscript = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun releaseRecognizer(cancelFirst: Boolean) {
        recognizer?.let { service ->
            if (cancelFirst) runCatching(service::cancel)
            runCatching(service::destroy)
        }
        recognizer = null
    }
}
