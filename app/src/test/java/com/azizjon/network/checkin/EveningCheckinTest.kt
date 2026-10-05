package com.azizjon.network.checkin

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EveningCheckinTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun beforeTheTimeItIsLaterToday() {
        val now = ZonedDateTime.of(2026, 10, 5, 18, 30, 0, 0, seoul)

        assertEquals(Duration.ofMinutes(150), EveningCheckin.delayUntil(21 * 60, now))
    }

    @Test
    fun atOrAfterTheTimeItIsTomorrow() {
        val at = ZonedDateTime.of(2026, 10, 5, 21, 0, 0, 0, seoul)
        val after = ZonedDateTime.of(2026, 10, 5, 22, 15, 0, 0, seoul)

        assertEquals(Duration.ofHours(24), EveningCheckin.delayUntil(21 * 60, at))
        assertEquals(Duration.ofMinutes(22 * 60 + 45), EveningCheckin.delayUntil(21 * 60, after))
    }

    @Test
    fun aRunHoursLateIsSkippedButALittleLateIsNot() {
        assertTrue(EveningCheckin.onTime(21 * 60, ZonedDateTime.of(2026, 10, 5, 21, 20, 0, 0, seoul)))
        // The phone was off overnight: no "did you meet anyone today?" at breakfast.
        assertFalse(EveningCheckin.onTime(21 * 60, ZonedDateTime.of(2026, 10, 6, 7, 30, 0, 0, seoul)))
        // A late-evening time still counts just after midnight.
        assertTrue(EveningCheckin.onTime(23 * 60 + 30, ZonedDateTime.of(2026, 10, 6, 0, 40, 0, 0, seoul)))
    }
}
