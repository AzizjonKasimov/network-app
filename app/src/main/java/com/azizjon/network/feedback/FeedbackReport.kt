package com.azizjon.network.feedback

import com.azizjon.network.data.AiFeedbackEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Turns the stored reports into one JSON document meant to be read elsewhere.
 *
 * JSON rather than prose because the point of a fixed label set is that the
 * result can be counted and grouped, and because whatever reads it next has no
 * access to this app's types. The document is self-describing: it carries its
 * own name, version, and a statement of whether it was redacted, so a file
 * found on its own months later still says what it is and how far to trust it.
 */
object FeedbackReport {
    const val REPORT_NAME = "network-app-assistant-feedback"
    const val REPORT_VERSION = 1

    private const val PURPOSE =
        "Assistant responses the app's owner marked as wrong, for diagnosing and fixing the " +
            "gateway prompts, schemas, and parsing in the Network App Android project. Each item " +
            "is a complete record of one failure: what was asked, what the assistant answered, " +
            "and which fault the owner assigned it."

    private const val REDACTED_NOTE =
        "Saved people are replaced by stable placeholders, and emails, links, and phone-shaped " +
            "numbers are removed. The same placeholder always means the same person within this " +
            "report. People not yet saved in the app cannot be detected and may still be named in " +
            "quoted text."

    private const val RAW_NOTE =
        "Not redacted. This report quotes real names and conversation text about real people. " +
            "Keep it out of version control, issues, and anywhere it could become public."

    /**
     * @param redactor applied to every quoted field; null exports the raw text.
     */
    fun build(
        items: List<AiFeedbackEntity>,
        appVersion: String,
        generatedAt: Long,
        redactor: FeedbackRedactor?,
    ): String {
        val clean: (String) -> String = redactor?.let { { text: String -> it.redact(text) } } ?: { it }
        return JSONObject()
            .put("report", REPORT_NAME)
            .put("reportVersion", REPORT_VERSION)
            .put("purpose", PURPOSE)
            .put("generatedAt", isoInstant(generatedAt))
            .put("appVersion", appVersion)
            .put("redacted", redactor != null)
            .put("redactionNote", if (redactor != null) REDACTED_NOTE else RAW_NOTE)
            .put("itemCount", items.size)
            .put("countsByLabel", counts(items.map { it.label }))
            .put("countsByStage", counts(items.map { it.stage }))
            .put(
                "labelMeanings",
                JSONObject().apply {
                    AiFeedbackLabel.entries.forEach { label -> put(label.id, label.description) }
                },
            )
            .put("items", JSONArray().apply { items.forEach { put(it.toJson(clean)) } })
            .toString(2)
    }

    /** The filename the report is written under. Sorts chronologically. */
    fun fileName(generatedAt: Long): String =
        "assistant-feedback-" + FILE_STAMP.format(Instant.ofEpochMilli(generatedAt)) + ".json"

    private fun AiFeedbackEntity.toJson(clean: (String) -> String) = JSONObject()
        .put("id", id)
        .put("recordedAt", isoInstant(createdAt))
        .put("appVersion", appVersion)
        .put("stage", stage)
        .put("label", label)
        .put("labelTitle", AiFeedbackLabel.titleFor(label))
        .put("ownerNote", clean(note))
        .put("userMessage", clean(userMessage))
        .put("assistantMessage", clean(assistantMessage))
        .put("assistantDetail", clean(assistantDetail))
        .put("previouslyExported", exportedAt != null)

    private fun counts(values: List<String>): JSONObject = JSONObject().apply {
        values.groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .forEach { (key, count) -> put(key, count) }
    }

    private fun isoInstant(timestamp: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timestamp))

    private val FILE_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC)
}
