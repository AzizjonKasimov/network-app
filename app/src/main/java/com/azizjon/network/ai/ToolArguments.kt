package com.azizjon.network.ai

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.json.JSONObject

/**
 * A tool call the phone refuses, with a message written for the assistant.
 *
 * The gateway validates argument types before a call arrives, so these are the
 * checks only the phone can make: whether an id exists, whether a record is a
 * duplicate, whether a date is in the future.
 */
class ToolFailure(message: String) : Exception(message)

internal fun JSONObject.requiredLong(key: String): Long {
    if (!has(key) || isNull(key)) throw ToolFailure("$key is required.")
    return runCatching { getLong(key) }.getOrElse { throw ToolFailure("$key must be a whole number.") }
}

internal fun JSONObject.optionalLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return runCatching { getLong(key) }.getOrElse { throw ToolFailure("$key must be a whole number.") }
}

internal fun JSONObject.optionalBoolean(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return runCatching { getBoolean(key) }.getOrElse { throw ToolFailure("$key must be true or false.") }
}

/** Trimmed text, or null when absent. An empty string is kept: it means clear the field. */
internal fun JSONObject.optionalText(key: String, maximum: Int): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key).trim()
    if (value.length > maximum) throw ToolFailure("$key is too long; keep it under $maximum characters.")
    return value
}

internal fun JSONObject.requiredText(key: String, maximum: Int): String =
    optionalText(key, maximum)?.takeIf(String::isNotEmpty) ?: throw ToolFailure("$key is required.")

internal fun JSONObject.optionalStrings(key: String): List<String>? {
    val array = optJSONArray(key) ?: return null
    return (0 until array.length()).map { array.optString(it).trim() }.filter(String::isNotEmpty)
}

private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

/**
 * Reads a date the assistant supplied.
 *
 * A bare `YYYY-MM-DD` means the start of that day on this phone, which is how
 * the person screens show dates. A full timestamp is taken as given. Either
 * way a date more than a few minutes ahead is refused: nothing the user tells
 * the assistant about happened in the future.
 */
internal fun parseToolDate(value: String?, zone: ZoneId, now: Instant): Long? {
    val instant = parseDateText(value, zone, endOfDay = false) ?: return null
    if (instant.isAfter(now.plusSeconds(300))) throw ToolFailure("${value?.trim()} is in the future; use the date it happened.")
    return instant.toEpochMilli()
}

/** A date bounding a search. The future is allowed, and a bare `to` date covers that whole day. */
internal fun parseRangeDate(value: String?, zone: ZoneId, endOfDay: Boolean): Long? =
    parseDateText(value, zone, endOfDay)?.toEpochMilli()

private fun parseDateText(value: String?, zone: ZoneId, endOfDay: Boolean): Instant? {
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return try {
        if (text.length == 10) {
            val day = LocalDate.parse(text, dayFormatter)
            if (endOfDay) day.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1) else day.atStartOfDay(zone).toInstant()
        } else {
            runCatching { OffsetDateTime.parse(text).toInstant() }.getOrElse { Instant.parse(text) }
        }
    } catch (error: DateTimeParseException) {
        throw ToolFailure("Dates look like 2026-09-14; \"$text\" is not one.")
    }
}

internal fun formatToolDate(epochMillis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().format(dayFormatter)
