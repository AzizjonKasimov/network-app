package com.azizjon.network.feedback

import com.azizjon.network.data.AiFeedbackEntity
import com.azizjon.network.data.PersonEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackReportTest {
    private val generatedAt = 1_788_912_000_000L

    private val items = listOf(
        feedback(
            id = 1,
            label = AiFeedbackLabel.WRONG_TARGET.id,
            stage = AiFeedbackEntity.Stage.PROPOSAL,
            userMessage = "Maria Gomez is hiring",
            assistantMessage = "I prepared changes for Maria Chen.",
        ),
        feedback(
            id = 2,
            label = AiFeedbackLabel.WRONG_TARGET.id,
            stage = AiFeedbackEntity.Stage.SEARCH,
            userMessage = "Who could help with funding?",
            assistantMessage = "Maria Chen might.",
            exportedAt = generatedAt - 1,
        ),
    )

    private val people = listOf(
        PersonEntity(id = 1, name = "Maria Gomez", createdAt = 0, updatedAt = 0),
        PersonEntity(id = 2, name = "Maria Chen", createdAt = 0, updatedAt = 0),
    )

    @Test
    fun theDocumentDescribesItselfAndCountsWhatItHolds() {
        val json = JSONObject(FeedbackReport.build(items, "0.9.0 (10)", generatedAt, redactor = null))

        assertEquals(FeedbackReport.REPORT_NAME, json.getString("report"))
        assertEquals(FeedbackReport.REPORT_VERSION, json.getInt("reportVersion"))
        assertEquals("2026-09-09T00:00:00Z", json.getString("generatedAt"))
        assertEquals("0.9.0 (10)", json.getString("appVersion"))
        assertEquals(2, json.getInt("itemCount"))
        assertEquals(2, json.getJSONObject("countsByLabel").getInt(AiFeedbackLabel.WRONG_TARGET.id))
        assertEquals(1, json.getJSONObject("countsByStage").getInt(AiFeedbackEntity.Stage.SEARCH))
        // Every label carries its meaning, so the file explains itself.
        assertTrue(json.getJSONObject("labelMeanings").has(AiFeedbackLabel.OTHER.id))
    }

    @Test
    fun aRedactedReportSaysSoAndCarriesNoRealNames() {
        val body = FeedbackReport.build(items, "0.9.0 (10)", generatedAt, FeedbackRedactor(people))
        val json = JSONObject(body)

        assertTrue(json.getBoolean("redacted"))
        assertFalse(body.contains("Gomez"))
        assertFalse(body.contains("Chen"))
        val first = json.getJSONArray("items").getJSONObject(0)
        assertEquals("Person 1 is hiring", first.getString("userMessage"))
        assertEquals("I prepared changes for Person 2.", first.getString("assistantMessage"))
    }

    @Test
    fun anUnredactedReportKeepsTheTextAndWarnsAboutIt() {
        val json = JSONObject(FeedbackReport.build(items, "0.9.0 (10)", generatedAt, redactor = null))

        assertFalse(json.getBoolean("redacted"))
        assertTrue(json.getString("redactionNote").contains("Not redacted"))
        assertEquals("Maria Gomez is hiring", json.getJSONArray("items").getJSONObject(0).getString("userMessage"))
    }

    @Test
    fun anAlreadyExportedItemIsMarkedSoRepeatedExportsAreObvious() {
        val json = JSONObject(FeedbackReport.build(items, "0.9.0 (10)", generatedAt, redactor = null))
        val entries = json.getJSONArray("items")

        assertFalse(entries.getJSONObject(0).getBoolean("previouslyExported"))
        assertTrue(entries.getJSONObject(1).getBoolean("previouslyExported"))
    }

    @Test
    fun theFileNameSortsChronologically() {
        assertEquals("assistant-feedback-20260909-000000.json", FeedbackReport.fileName(generatedAt))
    }

    private fun feedback(
        id: Long,
        label: String,
        stage: String,
        userMessage: String,
        assistantMessage: String,
        exportedAt: Long? = null,
    ) = AiFeedbackEntity(
        id = id,
        stage = stage,
        label = label,
        note = "",
        userMessage = userMessage,
        assistantMessage = assistantMessage,
        assistantDetail = "",
        appVersion = "0.9.0 (10)",
        createdAt = generatedAt - 10_000,
        exportedAt = exportedAt,
    )
}
