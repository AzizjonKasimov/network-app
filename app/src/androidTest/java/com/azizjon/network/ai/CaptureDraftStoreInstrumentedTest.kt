package com.azizjon.network.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The draft is encrypted at rest, which only works against a real keystore. */
@RunWith(AndroidJUnit4::class)
class CaptureDraftStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        CaptureDraftStore(context).clear()
    }

    @Test
    fun aDraftSurvivesTheProcessThatWroteIt() {
        val note = "Coffee with Sarah Chen, she moved to Monzo as a backend lead"
        CaptureDraftStore(context).write(note)

        // A fresh instance stands in for the next process after Android killed this one.
        assertEquals(note, CaptureDraftStore(context).read())
    }

    @Test
    fun anEmptiedDraftDoesNotComeBack() {
        CaptureDraftStore(context).write("half a thought")
        CaptureDraftStore(context).write("   ")

        assertEquals("", CaptureDraftStore(context).read())
    }

    @Test
    fun anAbsentDraftReadsAsEmptyRatherThanFailing() {
        CaptureDraftStore(context).clear()

        assertEquals("", CaptureDraftStore(context).read())
    }
}
