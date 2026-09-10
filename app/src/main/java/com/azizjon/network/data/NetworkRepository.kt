package com.azizjon.network.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class PersonDraft(
    val id: Long = 0,
    val name: String,
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
    val affiliations: List<AffiliationEntity> = emptyList(),
    val facts: List<FactEntity> = emptyList(),
) {
    fun person(id: Long): PersonEntity? = people.firstOrNull { it.id == id }
    fun interactionsFor(personId: Long): List<InteractionEntity> = interactions.filter { it.personId == personId }
    fun needsFor(personId: Long): List<NeedEntity> = needs.filter { it.personId == personId }
    fun capabilitiesFor(personId: Long): List<CapabilityEntity> = capabilities.filter { it.personId == personId }
    fun affiliationsFor(personId: Long): List<AffiliationEntity> = affiliations.filter { it.personId == personId }
    fun factsFor(personId: Long): List<FactEntity> = facts.filter { it.personId == personId }

    /** Positions the person still holds, current ones first for display. */
    fun currentAffiliationsFor(personId: Long): List<AffiliationEntity> =
        affiliationsFor(personId).filter { it.current }

    /** A one-line summary of where someone works now, for cards and lists. */
    fun affiliationSummary(personId: Long): String =
        currentAffiliationsFor(personId).filterNot { it.isEducation }.joinToString(" · ") { it.label }
}

class NetworkRepository(private val dao: NetworkDao) {
    fun observeSnapshot(): Flow<NetworkSnapshot> = combine(
        dao.observePeople(),
        dao.observeInteractions(),
        dao.observeNeeds(),
        dao.observeCapabilities(),
        dao.observeAffiliations(),
        dao.observeFacts(),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        NetworkSnapshot(
            people = values[0] as List<PersonEntity>,
            interactions = values[1] as List<InteractionEntity>,
            needs = values[2] as List<NeedEntity>,
            capabilities = values[3] as List<CapabilityEntity>,
            affiliations = values[4] as List<AffiliationEntity>,
            facts = values[5] as List<FactEntity>,
        )
    }

    suspend fun savePerson(draft: PersonDraft): Long {
        val now = System.currentTimeMillis()
        val existing = if (draft.id == 0L) null else dao.person(draft.id)
        val person = PersonEntity(
            id = draft.id,
            name = draft.name.trim(),
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

    suspend fun addFact(personId: Long, text: String) {
        val clean = text.trim()
        require(clean.isNotBlank()) { "A background fact is required" }
        val now = System.currentTimeMillis()
        dao.insertFact(FactEntity(personId = personId, text = clean, lastConfirmedAt = now, createdAt = now))
        dao.touchPerson(personId, now)
    }

    suspend fun deleteFact(item: FactEntity) {
        dao.deleteFact(item.id)
        dao.touchPerson(item.personId, System.currentTimeMillis())
    }

    suspend fun addAffiliation(personId: Long, organization: String, role: String, education: Boolean = false) {
        val cleanOrganization = organization.trim()
        val cleanRole = role.trim()
        require(cleanOrganization.isNotBlank() || cleanRole.isNotBlank()) {
            "An organization or a role is required"
        }
        val now = System.currentTimeMillis()
        dao.insertAffiliation(
            AffiliationEntity(
                personId = personId,
                organization = cleanOrganization,
                role = cleanRole,
                lastConfirmedAt = now,
                createdAt = now,
                kind = if (education) AffiliationEntity.KIND_EDUCATION else AffiliationEntity.KIND_WORK,
            ),
        )
        dao.touchPerson(personId, now)
    }

    suspend fun setAffiliationCurrent(item: AffiliationEntity, current: Boolean) {
        val now = System.currentTimeMillis()
        dao.saveAffiliation(item.copy(current = current, lastConfirmedAt = now))
        dao.touchPerson(item.personId, now)
    }

    suspend fun deleteAffiliation(item: AffiliationEntity) {
        dao.deleteAffiliation(item.id)
        dao.touchPerson(item.personId, System.currentTimeMillis())
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

    /**
     * Sends a note filed against the wrong person, and everything it created,
     * to the right one.
     */
    suspend fun moveInteraction(interactionId: Long, destination: MoveDestination): MoveResult =
        dao.moveInteraction(interactionId, destination, System.currentTimeMillis())

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

    fun observeFeedback(): Flow<List<AiFeedbackEntity>> = dao.observeFeedback()

    /**
     * Stores one report about a wrong assistant response.
     *
     * The caller passes the response text rather than an id because the chat
     * thread is memory-only: by the time the report is read the message it
     * describes no longer exists anywhere.
     */
    suspend fun recordFeedback(feedback: AiFeedbackEntity): Long {
        require(feedback.label.isNotBlank()) { "Choose what went wrong" }
        return dao.insertFeedback(
            feedback.copy(
                note = feedback.note.trim().take(MAX_FEEDBACK_NOTE_CHARACTERS),
                userMessage = feedback.userMessage.take(MAX_FEEDBACK_TEXT_CHARACTERS),
                assistantMessage = feedback.assistantMessage.take(MAX_FEEDBACK_TEXT_CHARACTERS),
                assistantDetail = feedback.assistantDetail.take(MAX_FEEDBACK_DETAIL_CHARACTERS),
            ),
        )
    }

    suspend fun allFeedback(): List<AiFeedbackEntity> = dao.allFeedback()

    suspend fun deleteFeedback(id: Long) = dao.deleteFeedback(id)

    suspend fun clearFeedback() = dao.clearFeedback()

    suspend fun snapshot(): NetworkSnapshot = NetworkSnapshot(
        people = dao.allPeople(),
        interactions = dao.allInteractions(),
        needs = dao.allNeeds(),
        capabilities = dao.allCapabilities(),
        affiliations = dao.allAffiliations(),
        facts = dao.allFacts(),
    )

    suspend fun replaceAll(snapshot: NetworkSnapshot, feedback: List<AiFeedbackEntity>) = dao.replaceAll(
        people = snapshot.people,
        interactions = snapshot.interactions,
        needs = snapshot.needs,
        capabilities = snapshot.capabilities,
        affiliations = snapshot.affiliations,
        facts = snapshot.facts,
        feedback = feedback,
    )

    companion object {
        /** Caps so one enormous note cannot bloat the database or a report. */
        const val MAX_FEEDBACK_NOTE_CHARACTERS = 1_000
        const val MAX_FEEDBACK_TEXT_CHARACTERS = 4_000
        const val MAX_FEEDBACK_DETAIL_CHARACTERS = 8_000
    }
}
