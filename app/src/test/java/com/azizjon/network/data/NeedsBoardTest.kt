package com.azizjon.network.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeedsBoardTest {
    @Test
    fun everyNeedLandsInExactlyOneListByWhetherItIsOpenAndWhetherTheUserHelped() {
        val board = snapshot(
            need(1, text = "Open, not helped"),
            need(2, text = "Open, helped", helpedAt = 50),
            need(3, text = "Closed, not helped", status = NeedEntity.STATUS_CLOSED),
            need(4, text = "Closed after helping", status = NeedEntity.STATUS_CLOSED, helpedAt = 60),
        ).needsByStage()

        assertEquals(listOf("Open, not helped"), board.texts(NeedStage.TO_HELP))
        assertEquals(listOf("Open, helped"), board.texts(NeedStage.HELPED))
        assertEquals(listOf("Closed after helping", "Closed, not helped"), board.texts(NeedStage.CLOSED).sorted())
    }

    @Test
    fun theUsersOwnNeedsAndArchivedPeopleAreLeftOut() {
        val board = snapshot(
            need(1, personId = OTHER, text = "Someone else's need"),
            need(2, personId = ME, text = "My own need"),
            need(3, personId = ARCHIVED, text = "An archived person's need"),
        ).needsByStage()

        assertEquals(listOf("Someone else's need"), board.texts(NeedStage.TO_HELP))
        assertTrue(board.getValue(NeedStage.HELPED).isEmpty())
        assertTrue(board.getValue(NeedStage.CLOSED).isEmpty())
    }

    @Test
    fun theFreshestNeedComesFirstAndHelpedOnesFollowTheMostRecentHelp() {
        val board = snapshot(
            need(1, text = "Mentioned long ago", confirmed = 10),
            need(2, text = "Mentioned last week", confirmed = 30),
            need(3, text = "Mentioned in between", confirmed = 20),
            need(4, text = "Helped first", confirmed = 40, helpedAt = 100),
            need(5, text = "Helped most recently", confirmed = 5, helpedAt = 200),
        ).needsByStage()

        assertEquals(
            listOf("Mentioned last week", "Mentioned in between", "Mentioned long ago"),
            board.texts(NeedStage.TO_HELP),
        )
        assertEquals(listOf("Helped most recently", "Helped first"), board.texts(NeedStage.HELPED))
    }

    @Test
    fun eachNeedCarriesThePersonWhoHasIt() {
        val item = snapshot(need(1, personId = OTHER, text = "Find a synthetic designer")).needsByStage()
            .getValue(NeedStage.TO_HELP).single()

        assertEquals("Synthetic Other", item.person.name)
    }

    private fun Map<NeedStage, List<NeedItem>>.texts(stage: NeedStage): List<String> = getValue(stage).map { it.need.text }

    private fun snapshot(vararg needs: NeedEntity) = NetworkSnapshot(
        people = listOf(
            PersonEntity(id = OTHER, name = "Synthetic Other", createdAt = 1, updatedAt = 1),
            PersonEntity(id = ME, name = "Synthetic Me", isSelf = true, createdAt = 1, updatedAt = 1),
            PersonEntity(id = ARCHIVED, name = "Synthetic Archived", archived = true, createdAt = 1, updatedAt = 1),
        ),
        needs = needs.toList(),
    )

    private fun need(
        id: Long,
        personId: Long = OTHER,
        text: String,
        status: String = NeedEntity.STATUS_ACTIVE,
        confirmed: Long = 1,
        helpedAt: Long? = null,
    ) = NeedEntity(
        id = id,
        personId = personId,
        text = text,
        status = status,
        lastConfirmedAt = confirmed,
        createdAt = 1,
        helpedAt = helpedAt,
    )

    private companion object {
        const val OTHER = 1L
        const val ME = 2L
        const val ARCHIVED = 3L
    }
}
