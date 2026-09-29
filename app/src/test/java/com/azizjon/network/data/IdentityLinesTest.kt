package com.azizjon.network.data

import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityLinesTest {
    @Test
    fun currentWorkThenPlaceThenHowTheUserKnowsThem() {
        val lines = NetworkSnapshot(
            people = listOf(person(1, location = "Berlin", relationship = "Met at a synthetic founders dinner")),
            affiliations = listOf(
                work(1, "Lumen Labs", "Advisor"),
                work(1, "Northwind", "CTO"),
                work(1, "Former Co", "Engineer", current = false),
                study(1, "Sample University", "MBA"),
            ),
        ).identityLines()

        assertEquals("Advisor at Lumen Labs · CTO at Northwind · Berlin · Met at a synthetic founders dinner", lines[1])
    }

    @Test
    fun studyStandsInOnlyWhenTheyHoldNoJob() {
        val lines = NetworkSnapshot(
            people = listOf(person(1), person(2, location = "Tashkent")),
            affiliations = listOf(
                study(1, "Sample University", "Economics"),
                study(2, "Synthetic Language School", ""),
                work(2, "Old Job", "Analyst", current = false),
            ),
        ).identityLines()

        assertEquals("Studying Economics at Sample University", lines[1])
        assertEquals("Studying at Synthetic Language School · Tashkent", lines[2])
    }

    @Test
    fun someoneWithNothingSavedGetsAnEmptyLine() {
        val lines = NetworkSnapshot(
            people = listOf(person(1, location = "  ")),
            affiliations = listOf(work(1, "Gone Co", "Founder", current = false)),
        ).identityLines()

        assertEquals("", lines[1])
    }

    private fun person(id: Long, location: String = "", relationship: String = "") =
        PersonEntity(id = id, name = "Synthetic $id", location = location, relationship = relationship, createdAt = 1, updatedAt = 1)

    private var nextId = 1L

    private fun work(personId: Long, organization: String, role: String, current: Boolean = true) = AffiliationEntity(
        id = nextId++, personId = personId, organization = organization, role = role, current = current,
        lastConfirmedAt = 1, createdAt = 1,
    )

    private fun study(personId: Long, organization: String, role: String) = work(personId, organization, role)
        .copy(kind = AffiliationEntity.KIND_EDUCATION)
}
