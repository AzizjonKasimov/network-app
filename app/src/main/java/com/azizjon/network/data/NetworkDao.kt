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

    @Query("SELECT * FROM affiliations ORDER BY current DESC, lastConfirmedAt DESC, id DESC")
    abstract fun observeAffiliations(): Flow<List<AffiliationEntity>>

    @Query("SELECT * FROM facts ORDER BY lastConfirmedAt DESC, id DESC")
    abstract fun observeFacts(): Flow<List<FactEntity>>

    @Query("SELECT * FROM people WHERE id = :id LIMIT 1")
    abstract suspend fun person(id: Long): PersonEntity?

    @Query("SELECT * FROM people WHERE name = :name COLLATE NOCASE LIMIT 1")
    abstract suspend fun personByName(name: String): PersonEntity?

    @Query("SELECT * FROM interactions WHERE id = :id LIMIT 1")
    abstract suspend fun interaction(id: Long): InteractionEntity?

    @Query("SELECT * FROM needs WHERE id = :id LIMIT 1")
    abstract suspend fun need(id: Long): NeedEntity?

    @Query("SELECT * FROM capabilities WHERE id = :id LIMIT 1")
    abstract suspend fun capability(id: Long): CapabilityEntity?

    @Query("SELECT * FROM affiliations WHERE id = :id LIMIT 1")
    abstract suspend fun affiliation(id: Long): AffiliationEntity?

    @Query("SELECT * FROM facts WHERE id = :id LIMIT 1")
    abstract suspend fun fact(id: Long): FactEntity?

    @Insert
    abstract suspend fun insertPerson(person: PersonEntity): Long

    @Update
    abstract suspend fun updatePerson(person: PersonEntity)

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

    @Query("DELETE FROM people WHERE id = :id")
    abstract suspend fun deletePersonById(id: Long)

    @Insert
    abstract suspend fun insertInteraction(interaction: InteractionEntity): Long

    @Update
    abstract suspend fun updateInteraction(interaction: InteractionEntity)

    @Insert
    abstract suspend fun insertNeed(need: NeedEntity): Long

    @Update
    abstract suspend fun updateNeed(need: NeedEntity)

    @Insert
    abstract suspend fun insertCapability(capability: CapabilityEntity): Long

    @Update
    abstract suspend fun updateCapability(capability: CapabilityEntity)

    @Query("DELETE FROM interactions WHERE id = :id")
    abstract suspend fun deleteInteraction(id: Long)

    @Query("DELETE FROM needs WHERE id = :id")
    abstract suspend fun deleteNeed(id: Long)

    // Single-column updates, so a tap on the Needs screen cannot overwrite an
    // edit the assistant made to the same row a moment earlier.
    @Query("UPDATE needs SET helpedAt = :helpedAt WHERE id = :id")
    abstract suspend fun setNeedHelpedAt(id: Long, helpedAt: Long?): Int

    @Query("UPDATE needs SET status = :status, lastConfirmedAt = :confirmedAt WHERE id = :id")
    abstract suspend fun setNeedStatus(id: Long, status: String, confirmedAt: Long): Int

    @Query("DELETE FROM capabilities WHERE id = :id")
    abstract suspend fun deleteCapability(id: Long)

    @Insert
    abstract suspend fun insertAffiliation(affiliation: AffiliationEntity): Long

    @Update
    abstract suspend fun saveAffiliation(affiliation: AffiliationEntity)

    @Query("DELETE FROM affiliations WHERE id = :id")
    abstract suspend fun deleteAffiliation(id: Long)

    @Insert
    abstract suspend fun insertFact(fact: FactEntity): Long

    @Update
    abstract suspend fun updateFact(fact: FactEntity)

    @Query("DELETE FROM facts WHERE id = :id")
    abstract suspend fun deleteFact(id: Long)

    @Query("UPDATE people SET updatedAt = :updatedAt WHERE id = :personId")
    abstract suspend fun touchPerson(personId: Long, updatedAt: Long)

    @Query("SELECT * FROM people ORDER BY id")
    abstract suspend fun allPeople(): List<PersonEntity>

    @Query("SELECT * FROM interactions ORDER BY id")
    abstract suspend fun allInteractions(): List<InteractionEntity>

    @Query("SELECT * FROM needs ORDER BY id")
    abstract suspend fun allNeeds(): List<NeedEntity>

    @Query("SELECT * FROM capabilities ORDER BY id")
    abstract suspend fun allCapabilities(): List<CapabilityEntity>

    @Query("SELECT * FROM affiliations ORDER BY id")
    abstract suspend fun allAffiliations(): List<AffiliationEntity>

    @Query("SELECT * FROM facts ORDER BY id")
    abstract suspend fun allFacts(): List<FactEntity>

    /** Every note and record a person owns, however it got there. */
    @Query(
        "SELECT (SELECT COUNT(*) FROM interactions WHERE personId = :personId) + " +
            "(SELECT COUNT(*) FROM needs WHERE personId = :personId) + " +
            "(SELECT COUNT(*) FROM capabilities WHERE personId = :personId) + " +
            "(SELECT COUNT(*) FROM affiliations WHERE personId = :personId) + " +
            "(SELECT COUNT(*) FROM facts WHERE personId = :personId)",
    )
    abstract suspend fun countOwnedRows(personId: Long): Int

    /** Records that name [interactionId] as the note they came from. */
    @Query(
        "SELECT (SELECT COUNT(*) FROM needs WHERE sourceInteractionId = :interactionId) + " +
            "(SELECT COUNT(*) FROM capabilities WHERE sourceInteractionId = :interactionId) + " +
            "(SELECT COUNT(*) FROM affiliations WHERE sourceInteractionId = :interactionId) + " +
            "(SELECT COUNT(*) FROM facts WHERE sourceInteractionId = :interactionId)",
    )
    abstract suspend fun countDerivedRows(interactionId: Long): Int

    @Query("UPDATE interactions SET personId = :personId WHERE id = :id")
    protected abstract suspend fun repointInteraction(id: Long, personId: Long): Int

    @Query("UPDATE needs SET personId = :personId WHERE sourceInteractionId = :interactionId")
    protected abstract suspend fun repointNeeds(interactionId: Long, personId: Long): Int

    @Query("UPDATE capabilities SET personId = :personId WHERE sourceInteractionId = :interactionId")
    protected abstract suspend fun repointCapabilities(interactionId: Long, personId: Long): Int

    @Query("UPDATE affiliations SET personId = :personId WHERE sourceInteractionId = :interactionId")
    protected abstract suspend fun repointAffiliations(interactionId: Long, personId: Long): Int

    @Query("UPDATE facts SET personId = :personId WHERE sourceInteractionId = :interactionId")
    protected abstract suspend fun repointFacts(interactionId: Long, personId: Long): Int

    @Query("UPDATE interactions SET personId = :toPersonId WHERE personId = :fromPersonId")
    protected abstract suspend fun moveAllInteractions(fromPersonId: Long, toPersonId: Long): Int

    @Query("UPDATE needs SET personId = :toPersonId WHERE personId = :fromPersonId")
    protected abstract suspend fun moveAllNeeds(fromPersonId: Long, toPersonId: Long): Int

    @Query("UPDATE capabilities SET personId = :toPersonId WHERE personId = :fromPersonId")
    protected abstract suspend fun moveAllCapabilities(fromPersonId: Long, toPersonId: Long): Int

    @Query("UPDATE affiliations SET personId = :toPersonId WHERE personId = :fromPersonId")
    protected abstract suspend fun moveAllAffiliations(fromPersonId: Long, toPersonId: Long): Int

    @Query("UPDATE facts SET personId = :toPersonId WHERE personId = :fromPersonId")
    protected abstract suspend fun moveAllFacts(fromPersonId: Long, toPersonId: Long): Int

    /**
     * Moves one note, and everything that note created, onto another person.
     *
     * A capture is filed against whoever the assistant resolved from the
     * message, and that is sometimes the wrong person - most often somebody
     * mentioned nearby rather than the person being described. Until this
     * existed the only remedy was deleting the records and retyping the note.
     *
     * Scoped to a single interaction on purpose. Derived records carry the id of
     * the interaction that created them, so "everything this note created" is an
     * exact set rather than a guess, and anything the person gained some other
     * way stays where it is. Profile fields are the deliberate exception: a
     * patch overwrote a column in place and leaves nothing to trace back, so it
     * cannot move and the caller says so before asking to confirm.
     */
    @Transaction
    open suspend fun moveInteraction(
        interactionId: Long,
        destination: MoveDestination,
        now: Long,
    ): MoveResult {
        val interaction = interaction(interactionId)
            ?: throw IllegalArgumentException("That note no longer exists")
        val source = interaction.personId

        val target = when (destination) {
            is MoveDestination.Existing ->
                person(destination.personId)
                    ?.also {
                        require(!it.archived) { "${it.name} is archived. Restore them before moving a note there." }
                    }
                    ?: throw IllegalArgumentException("The chosen person no longer exists")
            is MoveDestination.NewPerson -> {
                val name = destination.name.trim()
                require(name.isNotBlank()) { "Name is required" }
                require(name.length <= 200) { "That name is too long" }
                require(personByName(name) == null) { "$name already exists. Choose them from the list instead." }
                val id = savePerson(PersonEntity(name = name, createdAt = now, updatedAt = now))
                person(id) ?: throw IllegalStateException("The new person could not be created")
            }
        }
        require(target.id != source) { "That note is already on ${target.name}" }

        repointInteraction(interactionId, target.id)
        val moved = MoveResult(
            personId = target.id,
            personName = target.name,
            needs = repointNeeds(interactionId, target.id),
            capabilities = repointCapabilities(interactionId, target.id),
            affiliations = repointAffiliations(interactionId, target.id),
            facts = repointFacts(interactionId, target.id),
        )
        touchPerson(source, now)
        touchPerson(target.id, now)
        return moved
    }

    /**
     * Folds a duplicate entry into the one being kept, then deletes the duplicate.
     *
     * Every note and record moves, so nothing is lost with the deleted row. The
     * kept profile wins wherever it already says something; the duplicate only
     * fills fields the kept one left empty, and its tags and notes are added
     * rather than dropped.
     */
    @Transaction
    open suspend fun mergePeople(keepId: Long, mergeId: Long, now: Long): MergeResult {
        require(keepId != mergeId) { "Those are the same person" }
        val keep = person(keepId) ?: throw IllegalArgumentException("The person to keep no longer exists")
        val merge = person(mergeId) ?: throw IllegalArgumentException("The person to merge no longer exists")

        val moved = moveAllInteractions(mergeId, keepId) +
            moveAllNeeds(mergeId, keepId) +
            moveAllCapabilities(mergeId, keepId) +
            moveAllAffiliations(mergeId, keepId) +
            moveAllFacts(mergeId, keepId)
        savePerson(
            keep.copy(
                location = keep.location.ifBlank { merge.location },
                contact = keep.contact.ifBlank { merge.contact },
                relationship = keep.relationship.ifBlank { merge.relationship },
                tags = mergeTags(keep.tags, merge.tags),
                notes = listOf(keep.notes, merge.notes).map(String::trim).filter(String::isNotEmpty).distinct()
                    .joinToString("\n\n"),
                isSelf = keep.isSelf || merge.isSelf,
                archived = keep.archived && merge.archived,
                updatedAt = now,
            ),
        )
        deletePersonById(mergeId)
        return MergeResult(keptName = keep.name, mergedName = merge.name, movedRows = moved)
    }

    @Query("SELECT * FROM ai_feedback ORDER BY createdAt DESC, id DESC")
    abstract fun observeFeedback(): Flow<List<AiFeedbackEntity>>

    @Query("SELECT * FROM ai_feedback ORDER BY createdAt, id")
    abstract suspend fun allFeedback(): List<AiFeedbackEntity>

    @Insert
    abstract suspend fun insertFeedback(feedback: AiFeedbackEntity): Long

    @Query("DELETE FROM ai_feedback WHERE id = :id")
    abstract suspend fun deleteFeedback(id: Long)

    @Query("DELETE FROM ai_feedback")
    abstract suspend fun clearFeedback()

    @Query("DELETE FROM interactions")
    protected abstract suspend fun clearInteractions()

    @Query("DELETE FROM needs")
    protected abstract suspend fun clearNeeds()

    @Query("DELETE FROM capabilities")
    protected abstract suspend fun clearCapabilities()

    @Query("DELETE FROM affiliations")
    protected abstract suspend fun clearAffiliations()

    @Query("DELETE FROM facts")
    protected abstract suspend fun clearFacts()

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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun restoreAffiliations(affiliations: List<AffiliationEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun restoreFacts(facts: List<FactEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun restoreFeedback(feedback: List<AiFeedbackEntity>)

    @Transaction
    open suspend fun replaceAll(
        people: List<PersonEntity>,
        interactions: List<InteractionEntity>,
        needs: List<NeedEntity>,
        capabilities: List<CapabilityEntity>,
        affiliations: List<AffiliationEntity>,
        facts: List<FactEntity>,
        feedback: List<AiFeedbackEntity>,
    ) {
        clearFeedback()
        clearInteractions()
        clearNeeds()
        clearCapabilities()
        clearAffiliations()
        clearFacts()
        clearPeople()
        restorePeople(people)
        restoreInteractions(interactions)
        restoreNeeds(needs)
        restoreCapabilities(capabilities)
        restoreAffiliations(affiliations)
        restoreFacts(facts)
        restoreFeedback(feedback)
    }

    private fun mergeTags(first: String, second: String): String =
        (first.split(',') + second.split(','))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy { it.lowercase() }
            .joinToString(", ")
}
