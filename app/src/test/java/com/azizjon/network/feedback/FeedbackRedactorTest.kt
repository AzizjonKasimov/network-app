package com.azizjon.network.feedback

import com.azizjon.network.data.PersonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackRedactorTest {
    private val people = listOf(
        person(id = 1, name = "Maria Gomez", contact = "maria@example.test, +1 555 010 4477"),
        person(id = 2, name = "Maria Chen"),
        person(id = 3, name = "Sam Idris", isSelf = true),
    )
    private val redactor = FeedbackRedactor(people)

    @Test
    fun eachSavedPersonKeepsOneStablePlaceholder() {
        val redacted = redactor.redact("Maria Gomez introduced Maria Gomez to Maria Chen.")

        assertEquals("Person 1 introduced Person 1 to Person 2.", redacted)
    }

    @Test
    fun aSurnameAloneStillIdentifiesTheSamePlaceholder() {
        assertEquals("Person 2 works there", redactor.redact("Chen works there"))
    }

    @Test
    fun aSharedFirstNameDegradesInsteadOfNamingTheWrongPerson() {
        assertEquals(
            "${FeedbackRedactor.AMBIGUOUS_PERSON} mentioned it",
            redactor.redact("Maria mentioned it"),
        )
    }

    @Test
    fun theOwnerIsLabelledAsThemselves() {
        assertEquals("${FeedbackRedactor.SELF} asked about it", redactor.redact("Sam asked about it"))
    }

    @Test
    fun contactDetailsAreRemovedWhetherOrNotTheyWereSaved() {
        val redacted = redactor.redact(
            "Write to maria@example.test or someone-else@other.test, or call +1 555 010 4477.",
        )

        assertFalse(redacted.contains("@example.test"))
        assertFalse(redacted.contains("other.test"))
        assertFalse(redacted.contains("555"))
        assertTrue(redacted.contains(FeedbackRedactor.CONTACT))
    }

    @Test
    fun linksAreRemoved() {
        assertEquals("See ${FeedbackRedactor.LINK}", redactor.redact("See https://example.test/a/b?c=d"))
    }

    /**
     * A wrong-date report is worthless if the sweep eats the date, so the
     * phone-shaped rule has to stay above the length of a written date.
     */
    @Test
    fun datesAndOrdinaryQuantitiesSurvive() {
        val text = "On 2026-09-09 the product had 10,000 users and 12 employees."

        assertEquals(text, redactor.redact(text))
    }

    @Test
    fun aShortNameIsLeftAloneBecauseItWouldMatchOrdinaryWords() {
        val single = FeedbackRedactor(listOf(person(id = 1, name = "Jo")))

        assertEquals("Jo is joining", single.redact("Jo is joining"))
    }

    private fun person(id: Long, name: String, contact: String = "", isSelf: Boolean = false) = PersonEntity(
        id = id,
        name = name,
        contact = contact,
        isSelf = isSelf,
        createdAt = 0,
        updatedAt = 0,
    )
}
