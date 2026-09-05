package com.azizjon.network.ui

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechInputTest {
    @Test
    fun transcriptNormalizesWhitespaceAndAppendsWithoutSubmitting() {
        assertEquals(
            TranscriptAppendResult.Success("Hello world"),
            appendTranscript("", "  Hello   world  ", 100),
        )
        assertEquals(
            TranscriptAppendResult.Success("Existing note More detail"),
            appendTranscript("Existing note   ", " More   detail ", 100),
        )
    }

    @Test
    fun transcriptRejectsEmptyAndOverflowWithoutChangingText() {
        assertEquals(TranscriptAppendResult.Empty, appendTranscript("Existing", "   ", 100))
        assertEquals(TranscriptAppendResult.TooLong, appendTranscript("12345", "67890", 8))
    }

    @Test
    fun recognizerSelectionPrefersOnDeviceAndRequiresFallbackDisclosure() {
        assertEquals(
            SpeechRecognizerMode.ON_DEVICE,
            chooseSpeechRecognizerMode(35, onDeviceAvailable = true, systemRecognizerAvailable = true, fallbackAllowed = false),
        )
        assertEquals(
            SpeechRecognizerMode.NEEDS_FALLBACK_DISCLOSURE,
            chooseSpeechRecognizerMode(35, onDeviceAvailable = false, systemRecognizerAvailable = true, fallbackAllowed = false),
        )
        assertEquals(
            SpeechRecognizerMode.SYSTEM_FALLBACK,
            chooseSpeechRecognizerMode(30, onDeviceAvailable = false, systemRecognizerAvailable = true, fallbackAllowed = true),
        )
        assertEquals(
            SpeechRecognizerMode.UNAVAILABLE,
            chooseSpeechRecognizerMode(35, onDeviceAvailable = false, systemRecognizerAvailable = false, fallbackAllowed = true),
        )
    }

    @Test
    fun speechErrorsUseSafeActionableMessages() {
        assertTrue(speechErrorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS).contains("permission"))
        assertTrue(speechErrorMessage(SpeechRecognizer.ERROR_NO_MATCH).contains("No speech"))
        assertTrue(speechErrorMessage(SpeechRecognizer.ERROR_NETWORK).contains("could not be reached"))
    }
}
