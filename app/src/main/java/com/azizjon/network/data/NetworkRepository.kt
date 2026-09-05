package com.azizjon.network.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class PersonDraft(
    val id: Long = 0,
    val name: String,
    val organization: String = "",
    val role: String = "",
    val location: String = "",
    val contact: String = "",
    val relationship: String = "",
    val tags: String = "",
    val notes: String = "",
    val isSelf: Boolean = false,
    val archived: Boolean = false,
)

data class NetworkSnapshot(
    val people: List<PersonEntity> = emptyList(),
    val interactions: List<InteractionEntity> = emptyList(),
    val needs: List<NeedEntity> = emptyList(),
    val capabilities: List<CapabilityEntity> = emptyList(),
) {
    fun person(id: Long): PersonEntity? = people.firstOrNull { it.id == id }
    fun interactionsFor(personId: Long): List<InteractionEntity> = interactions.filter { it.personId == personId }
    fun needsFor(personId: Long): List<NeedEntity> = needs.filter { it.personId == personId }
    fun capabilitiesFor(personId: Long): List<CapabilityEntity> = capabilities.filter { it.personId == personId }
}

class NetworkRepository(private val dao: NetworkDao) {
    fun observeSnapshot(): Flow<NetworkSnapshot> = combine(
        dao.observePeople(),
        dao.observeInteractions(),
        dao.observeNeeds(),
        dao.observeCapabilities(),
    ) { people, interactions, needs, capabilities ->
        NetworkSnapshot(people, interactions, needs, capabilities)
    }

    suspend fun savePerson(draft: PersonDraft): Long {
        val now = System.currentTimeMillis()
        val existing = if (draft.id == 0L) null else dao.person(draft.id)
        val person = PersonEntity(
            id = draft.id,
            name = draft.name.trim(),
            organization = draft.organization.trim(),
            role = draft.role.trim(),
            location = draft.location.trim(),
            contact = draft.contact.trim(),
            relationship = draft.relationship.trim(),
            tags = draft.tags.trim(),
            notes = draft.notes.trim(),
            isSelf = draft.isSelf,
            archived = draft.archived,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        require(person.name.isNotBlank()) { "Name is required" }
        return dao.savePerson(person)
    }

    suspend fun deletePerson(person: PersonEntity) = dao.deletePerson(person)

    suspend fun addInteraction(personId: Long, note: String, occurredAt: Long = System.currentTimeMillis()) {
        val clean = note.trim()
        require(clean.isNotBlank()) { "Interaction note is required" }
        dao.insertInteraction(InteractionEntity(personId = personId, note = clean, occurredAt = occurredAt, createdAt = System.currentTimeMillis()))
        dao.touchPerson(personId, System.currentTimeMillis())
    }

    suspend fun addNeed(personId: Long, text: String) {
        val clean = text.trim()
        require(clean.isNotBlank()) { "Need or goal is required" }
        val now = System.currentTimeMillis()
        dao.insertNeed(NeedEntity(personId = personId, text = clean, lastConfirmedAt = now, createdAt = now))
        dao.touchPerson(personId, now)
    }

    suspend fun addCapability(personId: Long, text: String) {
        val clean = text.trim()
        require(clean.isNotBlank()) { "Capability or resource is required" }
        val now = System.currentTimeMillis()
        dao.insertCapability(CapabilityEntity(personId = personId, text = clean, lastConfirmedAt = now, createdAt = now))
        dao.touchPerson(personId, now)
    }

    suspend fun applyAiProposal(proposal: AiWriteProposal): AiWriteResult =
        dao.applyAiProposal(proposal, System.currentTimeMillis())

    suspend fun deleteInteraction(item: InteractionEntity) {
        dao.deleteInteraction(item.id)
        dao.touchPerson(item.personId, System.currentTimeMillis())
    }

    suspend fun deleteNeed(item: NeedEntity) {
        dao.deleteNeed(item.id)
        dao.touchPerson(item.personId, System.currentTimeMillis())
    }

    suspend fun deleteCapability(item: CapabilityEntity) {
        dao.deleteCapability(item.id)
        dao.touchPerson(item.personId, System.currentTimeMillis())
    }

    suspend fun snapshot(): NetworkSnapshot = NetworkSnapshot(
        people = dao.allPeople(),
        interactions = dao.allInteractions(),
        needs = dao.allNeeds(),
        capabilities = dao.allCapabilities(),
    )

    suspend fun replaceAll(snapshot: NetworkSnapshot) = dao.replaceAll(
        people = snapshot.people,
        interactions = snapshot.interactions,
        needs = snapshot.needs,
        capabilities = snapshot.capabilities,
    )
}
