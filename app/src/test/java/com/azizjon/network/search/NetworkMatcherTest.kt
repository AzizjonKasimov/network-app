package com.azizjon.network.search

import com.azizjon.network.data.CapabilityEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.PersonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMatcherTest {
    @Test
    fun capabilityProducesEvidenceBackedMatch() {
        val person = person(id = 1, name = "Sample Person")
        val snapshot = NetworkSnapshot(
            people = listOf(person),
            capabilities = listOf(
                CapabilityEntity(
                    id = 4,
                    personId = person.id,
                    text = "Android architecture and Kotlin mentoring",
                    lastConfirmedAt = 100,
                    createdAt = 90,
                ),
            ),
        )

        val result = NetworkMatcher.search(snapshot, "Who can help with Android?").single()

        assertEquals(person, result.person)
        assertEquals("Capability", result.evidence.first().kind)
        assertTrue(result.evidence.first().text.contains("Android"))
    }

    @Test
    fun archivedPeopleAreNotSuggested() {
        val snapshot = NetworkSnapshot(people = listOf(person(1, "Archived Person", archived = true)))

        assertTrue(NetworkMatcher.search(snapshot, "Archived").isEmpty())
    }

    @Test
    fun closedNeedsAndInactiveCapabilitiesAreNotSuggested() {
        val person = person(1, "Sample Person")
        val snapshot = NetworkSnapshot(
            people = listOf(person),
            needs = listOf(NeedEntity(2, 1, "Closed fundraising", NeedEntity.STATUS_CLOSED, 10, 10)),
            capabilities = listOf(CapabilityEntity(3, 1, "Inactive Android", 10, 10, active = false)),
        )

        assertTrue(NetworkMatcher.search(snapshot, "fundraising").isEmpty())
        assertTrue(NetworkMatcher.search(snapshot, "Android").isEmpty())
    }

    private fun person(id: Long, name: String, archived: Boolean = false) = PersonEntity(
        id = id,
        name = name,
        archived = archived,
        createdAt = 10,
        updatedAt = 20,
    )
}
