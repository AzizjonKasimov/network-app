package com.azizjon.network.ai

import com.azizjon.network.data.AffiliationEntity
import com.azizjon.network.data.InteractionEntity
import com.azizjon.network.data.MoveDestination
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovePlannerTest {
    @Test
    fun offersTheMostRecentNoteFirstWithWhatWouldTravelWithIt() {
        val outcome = MovePlanner.plan(snapshot(), "Synthetic Alex", "Synthetic Robin")

        val plan = (outcome as MoveOutcome.Ready).plan
        assertEquals("Synthetic Alex", plan.from.name)
        assertEquals("Synthetic Robin", plan.destinationName)
        assertEquals(MoveDestination.Existing(2L), plan.destination)
        assertFalse(plan.createsPerson)
        // Newest first, and the newest is the one preselected.
        assertEquals(listOf(20L, 10L), plan.candidates.map { it.interaction.id })
        assertEquals(20L, plan.selectedInteractionId)
        // Only what that note created is counted: one need plus one position.
        assertEquals(2, plan.selected.linkedRecords)
        assertEquals(0, plan.candidates.last().linkedRecords)
    }

    @Test
    fun anUnknownDestinationBecomesANewPersonRatherThanAFailure() {
        // The usual mistake is a stranger filed under whoever was mentioned
        // beside them, so the person the note belongs to often does not exist yet.
        val outcome = MovePlanner.plan(snapshot(), "Synthetic Alex", "Synthetic Wren")

        val plan = (outcome as MoveOutcome.Ready).plan
        assertTrue(plan.createsPerson)
        assertEquals(MoveDestination.NewPerson("Synthetic Wren"), plan.destination)
        assertEquals("Synthetic Wren", plan.destinationName)
    }

    @Test
    fun namesThatResolveToNobodyOrToTheSamePersonAreRefusedInPlainWords() {
        val unknownSource = MovePlanner.plan(snapshot(), "Synthetic Nobody", "Synthetic Robin")
        assertTrue((unknownSource as MoveOutcome.Problem).message.contains("Synthetic Nobody"))

        val samePerson = MovePlanner.plan(snapshot(), "Synthetic Alex", "Synthetic Alex")
        assertTrue(samePerson is MoveOutcome.Problem)

        val noDestination = MovePlanner.plan(snapshot(), "Synthetic Alex", "")
        assertTrue(noDestination is MoveOutcome.Problem)
    }

    @Test
    fun aPersonWithNothingFiledAgainstThemHasNothingToMove() {
        val outcome = MovePlanner.plan(snapshot(), "Synthetic Robin", "Synthetic Alex")

        assertTrue((outcome as MoveOutcome.Problem).message.contains("no notes"))
    }

    @Test
    fun archivedPeopleAreNotOfferedAsEitherEnd() {
        // Resolution runs over active people only, so an archived name reads as
        // unknown rather than quietly re-filing onto somebody hidden from view.
        val outcome = MovePlanner.plan(snapshot(), "Synthetic Archived", "Synthetic Robin")

        assertTrue(outcome is MoveOutcome.Problem)
    }

    @Test
    fun theNoteListIsCappedSoOneCardCannotBecomeABrowse() {
        val many = (1..12).map { index ->
            InteractionEntity(id = 100L + index, personId = 1L, note = "Note $index", occurredAt = index.toLong(), createdAt = 1)
        }
        val outcome = MovePlanner.plan(snapshot().copy(interactions = many), "Synthetic Alex", "Synthetic Robin")

        val plan = (outcome as MoveOutcome.Ready).plan
        assertEquals(MovePlanner.MAX_CANDIDATES, plan.candidates.size)
        assertEquals(112L, plan.selectedInteractionId)
    }

    private fun snapshot() = NetworkSnapshot(
        people = listOf(
            PersonEntity(id = 1, name = "Synthetic Alex", createdAt = 1, updatedAt = 1),
            PersonEntity(id = 2, name = "Synthetic Robin", createdAt = 1, updatedAt = 1),
            PersonEntity(id = 3, name = "Synthetic Archived", archived = true, createdAt = 1, updatedAt = 1),
        ),
        interactions = listOf(
            InteractionEntity(id = 10, personId = 1, note = "Older note.", occurredAt = 100, createdAt = 100),
            InteractionEntity(id = 20, personId = 1, note = "Newest note.", occurredAt = 200, createdAt = 200),
        ),
        needs = listOf(
            NeedEntity(id = 1, personId = 1, text = "Needs an introduction", lastConfirmedAt = 1, createdAt = 1, sourceInteractionId = 20),
            // Filed against the same person some other way, so it stays put.
            NeedEntity(id = 2, personId = 1, text = "Unlinked need", lastConfirmedAt = 1, createdAt = 1, sourceInteractionId = null),
        ),
        affiliations = listOf(
            AffiliationEntity(id = 1, personId = 1, organization = "Synthetic Co", role = "Advisor", lastConfirmedAt = 1, createdAt = 1, sourceInteractionId = 20),
        ),
    )
}
