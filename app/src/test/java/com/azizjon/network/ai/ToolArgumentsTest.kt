package com.azizjon.network.ai

import com.azizjon.network.data.RecordKind
import com.azizjon.network.data.RecordRef
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolArgumentsTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val now = Instant.parse("2026-09-14T12:00:00Z")

    @Test
    fun aBareDateIsThatDayOnThisPhone() {
        assertEquals(Instant.parse("2026-09-09T15:00:00Z").toEpochMilli(), parseToolDate("2026-09-10", seoul, now))
        assertEquals(Instant.parse("2026-09-01T08:00:00Z").toEpochMilli(), parseToolDate("2026-09-01T08:00:00Z", seoul, now))
        assertNull(parseToolDate("  ", seoul, now))
        assertNull(parseToolDate(null, seoul, now))
    }

    @Test
    fun theFutureAndNonsenseAreRefused() {
        assertThrows(ToolFailure::class.java) { parseToolDate("2026-09-16", seoul, now) }
        assertThrows(ToolFailure::class.java) { parseToolDate("last tuesday", seoul, now) }
    }

    @Test
    fun aSearchRangeMayReachTheFutureAndCoversItsLastDay() {
        assertEquals(Instant.parse("2026-12-31T14:59:59.999Z").toEpochMilli(), parseRangeDate("2026-12-31", seoul, endOfDay = true))
        assertEquals(Instant.parse("2026-12-30T15:00:00Z").toEpochMilli(), parseRangeDate("2026-12-31", seoul, endOfDay = false))
    }

    @Test
    fun textArgumentsAreTrimmedBoundedAndMayBeCleared() {
        val input = JSONObject().put("location", "  Seoul ").put("tags", "").put("name", "x".repeat(201))

        assertEquals("Seoul", input.optionalText("location", 500))
        assertEquals("", input.optionalText("tags", 500))
        assertNull(input.optionalText("missing", 500))
        assertThrows(ToolFailure::class.java) { input.optionalText("name", 200) }
        assertThrows(ToolFailure::class.java) { input.requiredText("tags", 500) }
        assertThrows(ToolFailure::class.java) { JSONObject().put("person_id", "seven").requiredLong("person_id") }
    }

    @Test
    fun recordReferencesParseOnlyWhatTheyShould() {
        assertEquals(RecordRef(RecordKind.NEED, 12), RecordRef.parse("need:12"))
        assertEquals(RecordRef(RecordKind.EDUCATION, 4), RecordRef.parse(" Education:4 "))
        assertEquals("fact:9", RecordRef(RecordKind.FACT, 9).toString())
        listOf("need", "need:", "need:0", "need:-3", "job:4", "need:twelve", "12").forEach { value ->
            assertNull(value, RecordRef.parse(value))
        }
    }
}
