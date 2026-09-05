package com.azizjon.network.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class NetworkDao {
    @Query("SELECT * FROM people ORDER BY isSelf DESC, updatedAt DESC, name COLLATE NOCASE")
    abstract fun observePeople(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM interactions ORDER BY occurredAt DESC, id DESC")
    abstract fun observeInteractions(): Flow<List<InteractionEntity>>

    @Query("SELECT * FROM needs ORDER BY lastConfirmedAt DESC, id DESC")
    abstract fun observeNeeds(): Flow<List<NeedEntity>>

    @Query("SELECT * FROM capabilities ORDER BY lastConfirmedAt DESC, id DESC")
    abstract fun observeCapabilities(): Flow<List<CapabilityEntity>>

    @Query("SELECT * FROM people WHERE id = :id LIMIT 1")
    abstract suspend fun person(id: Long): PersonEntity?

    @Query("SELECT * FROM people WHERE name = :name COLLATE NOCASE LIMIT 1")
    abstract suspend fun personByName(name: String): PersonEntity?

    @Query("SELECT * FROM interactions WHERE id = :id LIMIT 1")
    protected abstract suspend fun interaction(id: Long): InteractionEntity?

    @Query("SELECT * FROM needs WHERE id = :id LIMIT 1")
    protected abstract suspend fun need(id: Long): NeedEntity?

    @Query("SELECT * FROM capabilities WHERE id = :id LIMIT 1")
    protected abstract suspend fun capability(id: Long): CapabilityEntity?

    @Insert
    protected abstract suspend fun insertPerson(person: PersonEntity): Long

    @Update
    protected abstract suspend fun updatePerson(person: PersonEntity)

    @Query("UPDATE people SET isSelf = 0 WHERE isSelf = 1 AND id != :keepId")
    protected abstract suspend fun clearOtherSelfRecords(keepId: Long)

    @Transaction
    open suspend fun savePerson(person: PersonEntity): Long {
        val id = if (person.id == 0L) insertPerson(person) else {
            updatePerson(person)
            person.id
        }
        if (person.isSelf) clearOtherSelfRecords(id)
        return id
    }

    @Delete
    abstract suspend fun deletePerson(person: PersonEntity)

    @Insert
    abstract suspend fun insertInteraction(interaction: InteractionEntity): Long

    @Update
    protected abstract suspend fun updateInteraction(interaction: InteractionEntity)

    @Insert
    abstract suspend fun insertNeed(need: NeedEntity): Long

    @Update
    protected abstract suspend fun updateNeed(need: NeedEntity)

    @Insert
    abstract suspend fun insertCapability(capability: CapabilityEntity): Long

    @Update
    protected abstract suspend fun updateCapability(capability: CapabilityEntity)

    @Query("DELETE FROM interactions WHERE id = :id")
    abstract suspend fun deleteInteraction(id: Long)

    @Query("DELETE FROM needs WHERE id = :id")
    abstract suspend fun deleteNeed(id: Long)

    @Query("DELETE FROM capabilities WHERE id = :id")
    abstract suspend fun deleteCapability(id: Long)

    @Query("UPDATE people SET updatedAt = :updatedAt WHERE id = :personId")
    abstract suspend fun touchPerson(personId: Long, updatedAt: Long)

    @Transaction
    open suspend fun applyAiProposal(proposal: AiWriteProposal, now: Long): AiWriteResult {
        validateProposal(proposal, now)
        val current = proposal.targetPersonId?.let { id ->
            person(id)?.also { require(!it.archived) { "AI changes cannot target an archived person" } }
                ?: throw IllegalArgumentException("The selected person no longer exists")
        }
        if (current == null) {
            require(personByName(proposal.targetName.trim()) == null) {
                "A person with this name already exists; choose the existing person"
            }
        }

        val patches = proposal.profilePatches.filter { it.selected }
        val base = current ?: PersonEntity(
            name = proposal.targetName.trim(),
            createdAt = now,
            updatedAt = now,
        )
        val changed = patches.fold(base) { person, patch -> person.withPatch(patch) }
            .copy(updatedAt = now)
        require(changed.name.isNotBlank()) { "Name is required" }
        val personId = savePerson(changed)

        val auditId = insertInteraction(
            InteractionEntity(
                personId = personId,
                note = proposal.rawInput,
                occurredAt = proposal.occurredAt,
                createdAt = now,
                origin = InteractionEntity.ORIGIN_AI_REVIEWED,
            ),
        )

        proposal.newNeeds.filter { it.selected }.forEach { addition ->
            insertNeed(
                NeedEntity(
                    personId = personId,
                    text = addition.text.trim(),
                    lastConfirmedAt = proposal.occurredAt,
                    createdAt = now,
                    sourceInteractionId = auditId,
                ),
            )
        }
        proposal.newCapabilities.filter { it.selected }.forEach { addition ->
            insertCapability(
                CapabilityEntity(
                    personId = personId,
                    text = addition.text.trim(),
                    lastConfirmedAt = proposal.occurredAt,
                    createdAt = now,
                    sourceInteractionId = auditId,
                ),
            )
        }
        proposal.interactionEdits.filter { it.selected }.forEach { edit ->
            val existing = interaction(edit.id)
                ?: throw IllegalArgumentException("An interaction selected for editing no longer exists")
            require(existing.personId == personId) { "An interaction belongs to a different person" }
            updateInteraction(existing.copy(note = edit.note.trim(), occurredAt = edit.occurredAt))
        }
        proposal.needEdits.filter { it.selected }.forEach { edit ->
            val existing = need(edit.id)
                ?: throw IllegalArgumentException("A need selected for editing no longer exists")
            require(existing.personId == personId) { "A need belongs to a different person" }
            updateNeed(
                existing.copy(
                    text = edit.text.trim(),
                    status = edit.status,
                    lastConfirmedAt = edit.lastConfirmedAt,
                ),
            )
        }
        proposal.capabilityEdits.filter { it.selected }.forEach { edit ->
            val existing = capability(edit.id)
                ?: throw IllegalArgumentException("A capability selected for editing no longer exists")
            require(existing.personId == personId) { "A capability belongs to a different person" }
            updateCapability(
                existing.copy(
                    text = edit.text.trim(),
                    active = edit.active,
                    lastConfirmedAt = edit.lastConfirmedAt,
                ),
            )
        }
        return AiWriteResult(personId = personId, auditInteractionId = auditId)
    }

    @Query("SELECT * FROM people ORDER BY id")
    abstract suspend fun allPeople(): List<PersonEntity>

    @Query("SELECT * FROM interactions ORDER BY id")
    abstract suspend fun allInteractions(): List<InteractionEntity>

    @Query("SELECT * FROM needs ORDER BY id")
    abstract suspend fun allNeeds(): List<NeedEntity>

    @Query("SELECT * FROM capabilities ORDER BY id")
    abstract suspend fun allCapabilities(): List<CapabilityEntity>

    @Query("DELETE FROM interactions")
    protected abstract suspend fun clearInteractions()

    @Query("DELETE FROM needs")
    protected abstract suspend fun clearNeeds()

    @Query("DELETE FROM capabilities")
    protected abstract suspend fun clearCapabilities()

    @Query("DELETE FROM people")
    protected abstract suspend fun clearPeople()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun restorePeople(people: List<PersonEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun restoreInteractions(interactions: List<InteractionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun restoreNeeds(needs: List<NeedEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun restoreCapabilities(capabilities: List<CapabilityEntity>)

    @Transaction
    open suspend fun replaceAll(
        people: List<PersonEntity>,
        interactions: List<InteractionEntity>,
        needs: List<NeedEntity>,
        capabilities: List<CapabilityEntity>,
    ) {
        clearInteractions()
        clearNeeds()
        clearCapabilities()
        clearPeople()
        restorePeople(people)
        restoreInteractions(interactions)
        restoreNeeds(needs)
        restoreCapabilities(capabilities)
    }

    private fun validateProposal(proposal: AiWriteProposal, now: Long) {
        require(proposal.rawInput.isNotBlank()) { "The original note is empty" }
        require(proposal.rawInput.length <= 4_000) { "The original note is too long" }
        require(proposal.targetName.trim().isNotBlank()) { "The assistant did not identify a person" }
        require(proposal.targetName.trim().length <= 200) { "The person's name is too long" }
        require(proposal.occurredAt in 1..(now + 5 * 60_000L)) { "The interaction date is invalid" }
        require(proposal.interactionOnlyFacts.size <= 50) { "The assistant returned too many interaction-only facts" }
        require(proposal.interactionOnlyFacts.distinctBy { it.trim().lowercase() }.size == proposal.interactionOnlyFacts.size) {
            "The assistant returned duplicate interaction-only facts"
        }
        proposal.interactionOnlyFacts.forEach { fact ->
            require(fact.isNotBlank() && fact.length <= 500) { "An interaction-only fact is invalid" }
        }

        val selectedPatches = proposal.profilePatches.filter { it.selected }
        require(selectedPatches.map { it.field }.distinct().size == selectedPatches.size) {
            "The assistant proposed the same profile field more than once"
        }
        selectedPatches.forEach { patch ->
            val maximum = if (patch.field == ProfileField.NOTES) 4_000 else if (patch.field == ProfileField.NAME) 200 else 500
            require(patch.value.length <= maximum) { "A proposed profile value is too long" }
            if (patch.field == ProfileField.NAME) require(patch.value.trim().isNotBlank()) { "Name is required" }
        }

        proposal.newNeeds.filter { it.selected }.forEach { validateRecordText(it.text) }
        proposal.newCapabilities.filter { it.selected }.forEach { validateRecordText(it.text) }
        require(proposal.interactionEdits.filter { it.selected }.map { it.id }.distinct().size == proposal.interactionEdits.count { it.selected }) {
            "The assistant proposed the same interaction more than once"
        }
        require(proposal.needEdits.filter { it.selected }.map { it.id }.distinct().size == proposal.needEdits.count { it.selected }) {
            "The assistant proposed the same need more than once"
        }
        require(proposal.capabilityEdits.filter { it.selected }.map { it.id }.distinct().size == proposal.capabilityEdits.count { it.selected }) {
            "The assistant proposed the same capability more than once"
        }
        proposal.interactionEdits.filter { it.selected }.forEach { edit ->
            require(edit.id > 0) { "An interaction ID is invalid" }
            require(edit.note.isNotBlank() && edit.note.length <= 4_000) { "An interaction edit is invalid" }
            require(edit.occurredAt in 1..(now + 5 * 60_000L)) { "An interaction date is invalid" }
        }
        proposal.needEdits.filter { it.selected }.forEach { edit ->
            require(edit.id > 0) { "A need ID is invalid" }
            validateRecordText(edit.text)
            require(edit.status == NeedEntity.STATUS_ACTIVE || edit.status == NeedEntity.STATUS_CLOSED) {
                "A need status is invalid"
            }
            require(edit.lastConfirmedAt in 1..(now + 5 * 60_000L)) { "A need date is invalid" }
        }
        proposal.capabilityEdits.filter { it.selected }.forEach { edit ->
            require(edit.id > 0) { "A capability ID is invalid" }
            validateRecordText(edit.text)
            require(edit.lastConfirmedAt in 1..(now + 5 * 60_000L)) { "A capability date is invalid" }
        }
    }

    private fun validateRecordText(text: String) {
        require(text.isNotBlank() && text.length <= 1_000) { "A proposed record is invalid" }
    }

    private fun PersonEntity.withPatch(patch: ProfilePatch): PersonEntity = when (patch.field) {
        ProfileField.NAME -> copy(name = patch.value.trim())
        ProfileField.ORGANIZATION -> copy(organization = patch.value.trim())
        ProfileField.ROLE -> copy(role = patch.value.trim())
        ProfileField.LOCATION -> copy(location = patch.value.trim())
        ProfileField.CONTACT -> copy(contact = patch.value.trim())
        ProfileField.RELATIONSHIP -> copy(relationship = patch.value.trim())
        ProfileField.TAGS -> copy(tags = patch.value.trim())
        ProfileField.NOTES -> copy(notes = patch.value.trim())
    }
}
