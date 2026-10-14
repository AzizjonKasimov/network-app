package com.azizjon.network.checkin

import com.azizjon.network.data.PersonEntity
import com.azizjon.network.data.PersonNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleByNameTest {
    private val minsu = person(1, "Minsu Kim", "Minsu (Lumen CTO)\n김민수")
    private val ana = person(2, "Ana Lee")

    @Test
    fun aMainNameMatchesWithOrWithoutABracketedAside() {
        val names = PeopleByName(listOf(minsu, ana))

        assertEquals(listOf(ana), names.find("ana lee"))
        assertEquals(listOf(ana), names.find("Ana Lee (Northwind)"))
        assertTrue(names.find("Ana").isEmpty())
    }

    @Test
    fun anOtherNameMatchesOnlyWhole() {
        val names = PeopleByName(listOf(minsu, ana))

        assertEquals(listOf(minsu), names.find("Minsu (Lumen CTO)"))
        assertEquals(listOf(minsu), names.find("minsu (lumen cto) 🙂"))
        assertEquals(listOf(minsu), names.find("김민수"))
        // Another Minsu, told apart by the bracket.
        assertTrue(names.find("Minsu (Gym)").isEmpty())
        assertTrue(names.find("Minsu").isEmpty())
    }

    @Test
    fun aPersonFoundUnderTwoOfTheirNamesIsFoundOnce() {
        val names = PeopleByName(listOf(person(1, "Minsu Kim", "Minsu Kim (Lumen)")))

        assertEquals(1, names.find("Minsu Kim (Lumen)").size)
    }

    @Test
    fun namesThatCollideFindEveryoneTheyCouldBe() {
        val names = PeopleByName(listOf(person(1, "Ana Lee"), person(2, "Ana Park", "Ana Lee")))

        assertEquals(listOf(1L, 2L), names.find("Ana Lee").map { it.id }.sorted())
    }

    @Test
    fun aPartOfASavedNameSuggestsThePerson() {
        val names = PeopleByName(listOf(person(1, "Minsu Kim"), ana))

        assertEquals(listOf(1L), names.likely("Minsu (Lumen CTO)").map { it.id })
        assertEquals(listOf(1L), names.likely("Kim Minsu CTO").map { it.id })
        assertEquals(listOf(1L), names.likely("Minsu K.").map { it.id })
        assertTrue(names.likely("Minsu Park").isEmpty())
        // An initial alone is not enough.
        assertTrue(names.likely("K.").isEmpty())
    }

    @Test
    fun otherNamesCountTowardsASuggestion() {
        val names = PeopleByName(listOf(person(1, "Minsu Kim", "김민수")))

        assertEquals(listOf(1L), names.likely("김민수 대표님").map { it.id })
    }

    @Test
    fun aNameThatFitsTooManyPeopleSuggestsNobody() {
        val kims = (1L..4L).map { person(it, "Synthetic$it Kim") }

        assertEquals(3, PeopleByName(kims.take(3)).likely("Kim").size)
        assertTrue(PeopleByName(kims).likely("Kim").isEmpty())
    }

    @Test
    fun archivedPeopleAndTheUserAreNotSuggested() {
        val names = PeopleByName(listOf(person(1, "Minsu Kim", archived = true), person(2, "Minsu Park", self = true)))

        assertTrue(names.likely("Minsu").isEmpty())
    }

    @Test
    fun otherNamesAreStoredCleanly() {
        val stored = PersonNames.clean(
            "Minsu Kim",
            listOf("  Minsu   (Lumen CTO) ", "minsu (lumen cto)", "", "Minsu Kim", "🚀", "김민수\n"),
        )

        assertEquals("Minsu (Lumen CTO)\n김민수", stored)
        assertEquals(listOf("Minsu (Lumen CTO)", "김민수"), PersonNames.split(stored))
        assertEquals(PersonNames.MAX_NAMES, PersonNames.split(PersonNames.clean("Ana", (1..30).map { "Name $it" })).size)
    }

    private fun person(id: Long, name: String, aliases: String = "", archived: Boolean = false, self: Boolean = false) =
        PersonEntity(id = id, name = name, aliases = aliases, archived = archived, isSelf = self, createdAt = 1, updatedAt = 1)
}
