package com.azizjon.network.ai

import com.azizjon.network.data.PersonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonResolverTest {
    @Test
    fun exactNameIsSelectedWithoutSendingNetworkData() {
        val alice = person(1, "Alice Example")
        val result = PersonResolver.resolve(listOf(alice, person(2, "Bob Sample")), "alice example")

        assertEquals(alice, result.exact)
        assertEquals(emptyList<Any>(), result.suggestions)
    }

    @Test
    fun partialNameRequiresAChoiceInsteadOfAutoSelecting() {
        val result = PersonResolver.resolve(
            listOf(person(1, "Alex Kim"), person(2, "Alex Park")),
            "Alex",
        )

        assertNull(result.exact)
        assertEquals(listOf("Alex Kim", "Alex Park"), result.suggestions.map { it.name })
    }

    @Test
    fun archivedPeopleAreNeverCandidates() {
        val result = PersonResolver.resolve(listOf(person(1, "Archived Person", archived = true)), "Archived Person")

        assertNull(result.exact)
        assertEquals(emptyList<Any>(), result.suggestions)
    }

    @Test
    fun noteNamingOneSavedPersonSkipsTheAssistant() {
        val sarah = person(1, "Sarah Chen")
        val match = PersonResolver.resolveFromNote(
            listOf(sarah, person(2, "Marcus Webb")),
            "Caught up with Sarah Chen about the design partner search.",
        )

        assertEquals(sarah, match)
    }

    @Test
    fun aUniqueFirstNameIsEnoughToSkipTheAssistant() {
        val sarah = person(1, "Sarah Chen")
        val match = PersonResolver.resolveFromNote(
            listOf(sarah, person(2, "Marcus Webb")),
            "Called sarah today, she is hiring.",
        )

        assertEquals(sarah, match)
    }

    @Test
    fun aFirstNameTwoPeopleShareFallsBackToTheAssistant() {
        val match = PersonResolver.resolveFromNote(
            listOf(person(1, "Alex Kim"), person(2, "Alex Park")),
            "Alex mentioned a new role.",
        )

        assertNull(match)
    }

    @Test
    fun aNoteNamingTwoPeopleFallsBackToTheAssistant() {
        val match = PersonResolver.resolveFromNote(
            listOf(person(1, "Sarah Chen"), person(2, "Marcus Webb")),
            "Met Sarah Chen and Marcus Webb at the meetup.",
        )

        assertNull(match)
    }

    @Test
    fun aNameThatIsAlsoAnOrdinaryWordFallsBackToTheAssistant() {
        val match = PersonResolver.resolveFromNote(
            listOf(person(1, "Mark Diaz")),
            "Remember to mark this as done.",
        )

        assertNull(match)
    }

    @Test
    fun archivedPeopleAreNeverMatchedFromANote() {
        val match = PersonResolver.resolveFromNote(
            listOf(person(1, "Sarah Chen", archived = true)),
            "Caught up with Sarah Chen today.",
        )

        assertNull(match)
    }

    @Test
    fun anUnknownNameFallsBackToTheAssistant() {
        val match = PersonResolver.resolveFromNote(
            listOf(person(1, "Sarah Chen")),
            "Met a new founder called Priya at the conference.",
        )

        assertNull(match)
    }

    private fun person(id: Long, name: String, archived: Boolean = false) = PersonEntity(
        id = id,
        name = name,
        archived = archived,
        createdAt = 1,
        updatedAt = 2,
    )
}
