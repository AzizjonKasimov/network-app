package com.azizjon.network.ai

import com.azizjon.network.data.PersonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonResolverTest {
    @Test
    fun exactNameIsSelected() {
        val alice = person(1, "Alice Example")
        val result = PersonResolver.resolve(listOf(alice, person(2, "Bob Sample")), "alice example")

        assertEquals(alice, result.exact)
        assertEquals(emptyList<Any>(), result.suggestions)
    }

    @Test
    fun partialNameOffersEveryCloseMatchInsteadOfPickingOne() {
        val result = PersonResolver.resolve(
            listOf(person(1, "Alex Kim"), person(2, "Alex Park")),
            "Alex",
        )

        assertNull(result.exact)
        assertEquals(listOf("Alex Kim", "Alex Park"), result.suggestions.map { it.name })
    }

    @Test
    fun archivedPeopleAreOnlyCandidatesWhenAskedFor() {
        val archived = person(1, "Archived Person", archived = true)

        val hidden = PersonResolver.resolve(listOf(archived), "Archived Person")
        assertNull(hidden.exact)
        assertEquals(emptyList<Any>(), hidden.suggestions)

        assertEquals(archived, PersonResolver.resolve(listOf(archived), "Archived Person", includeArchived = true).exact)
    }

    private fun person(id: Long, name: String, archived: Boolean = false) = PersonEntity(
        id = id,
        name = name,
        archived = archived,
        createdAt = 1,
        updatedAt = 2,
    )
}
