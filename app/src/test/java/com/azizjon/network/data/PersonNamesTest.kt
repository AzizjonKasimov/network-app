package com.azizjon.network.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonNamesTest {
    private val jordan = PersonEntity(id = 1, name = "Jordan", aliases = "Jordan (Climate meetup)", createdAt = 1, updatedAt = 1)
    private val renamed = PersonNames.withNames(jordan.copy(name = "Jordan Kim"), jordan, jordan.otherNames)

    @Test
    fun aRenameKeepsTheOldNameFirstAndRemembersItWasTheMainName() {
        assertEquals(listOf("Jordan", "Jordan (Climate meetup)"), renamed.otherNames)
        assertEquals(listOf("Jordan"), renamed.formerNames)
        // The same name written differently is no rename.
        assertTrue(PersonNames.withNames(jordan.copy(name = "JORDAN"), jordan, jordan.otherNames).formerNames.isEmpty())
    }

    @Test
    fun aFormerNameLastsOnlyWhileItIsAnOtherName() {
        // Taken off on the person screen.
        assertTrue(PersonNames.withNames(renamed, renamed, listOf("Jordan (Climate meetup)")).formerNames.isEmpty())

        // Renamed back: the main name is neither another name nor a former one.
        val back = PersonNames.withNames(renamed.copy(name = "Jordan"), renamed, renamed.otherNames)
        assertEquals(listOf("Jordan Kim", "Jordan (Climate meetup)"), back.otherNames)
        assertEquals(listOf("Jordan Kim"), back.formerNames)
    }
}
