package com.azizjon.network.data

import androidx.room.withTransaction

/** The kinds of record that hang off a person, as the assistant names them. */
enum class RecordKind(val id: String, val label: String) {
    POSITION("position", "position"),
    EDUCATION("education", "education"),
    NEED("need", "need"),
    CAPABILITY("capability", "capability"),
    FACT("fact", "background fact"),
    ;

    /** Work and study are both rows in the affiliations table. */
    val isAffiliation: Boolean get() = this == POSITION || this == EDUCATION

    companion object {
        fun parse(value: String): RecordKind? = entries.firstOrNull { it.id == value.trim().lowercase() }
    }
}

/**
 * A record's address, written `kind:id` - `need:12`, `education:4`.
 *
 * Ids are only unique within a table, so the kind is part of the address. Work
 * and study share a table, so either prefix reaches the same affiliation row.
 */
data class RecordRef(val kind: RecordKind, val id: Long) {
    override fun toString(): String = "${kind.id}:$id"

    companion object {
        fun parse(value: String): RecordRef? {
            val parts = value.trim().split(':', limit = 2)
            if (parts.size != 2) return null
            val kind = RecordKind.parse(parts[0]) ?: return null
            val id = parts[1].trim().toLongOrNull()?.takeIf { it > 0 } ?: return null
            return RecordRef(kind, id)
        }

        fun of(row: AffiliationEntity) = RecordRef(if (row.isEducation) RecordKind.EDUCATION else RecordKind.POSITION, row.id)
        fun of(row: NeedEntity) = RecordRef(RecordKind.NEED, row.id)
        fun of(row: CapabilityEntity) = RecordRef(RecordKind.CAPABILITY, row.id)
        fun of(row: FactEntity) = RecordRef(RecordKind.FACT, row.id)
    }
}

/** One stored row, as it was before a change or as it became after it. */
sealed interface RowImage {
    /** Identity shared by every image of the same row: table plus id. */
    val key: String

    /** The person who owns the row. A person owns themself. */
    val ownerId: Long

    /** The note a record came from, if any. */
    val sourceNoteId: Long? get() = null

    data class Person(val row: PersonEntity) : RowImage {
        override val key: String get() = "person:${row.id}"
        override val ownerId: Long get() = row.id
    }

    data class Note(val row: InteractionEntity) : RowImage {
        override val key: String get() = "note:${row.id}"
        override val ownerId: Long get() = row.personId
    }

    data class Need(val row: NeedEntity) : RowImage {
        override val key: String get() = "need:${row.id}"
        override val ownerId: Long get() = row.personId
        override val sourceNoteId: Long? get() = row.sourceInteractionId
    }

    data class Capability(val row: CapabilityEntity) : RowImage {
        override val key: String get() = "capability:${row.id}"
        override val ownerId: Long get() = row.personId
        override val sourceNoteId: Long? get() = row.sourceInteractionId
    }

    data class Affiliation(val row: AffiliationEntity) : RowImage {
        override val key: String get() = "affiliation:${row.id}"
        override val ownerId: Long get() = row.personId
        override val sourceNoteId: Long? get() = row.sourceInteractionId
    }

    data class Fact(val row: FactEntity) : RowImage {
        override val key: String get() = "fact:${row.id}"
        override val ownerId: Long get() = row.personId
        override val sourceNoteId: Long? get() = row.sourceInteractionId
    }
}

/** One write. [before] is null for an insert, [after] is null for a delete. */
data class RowChange(val before: RowImage?, val after: RowImage?) {
    init {
        require(before != null || after != null) { "A change needs a before or an after" }
    }

    val key: String get() = (after ?: before)!!.key
}

/** Undo would overwrite something written after the changes it is undoing. */
class UndoConflictException(message: String) : IllegalStateException(message)

/** What a block of writes returned, and every row it changed. */
data class Recorded<T>(val value: T, val changes: List<RowChange>)

/**
 * Writes rows while remembering what each write replaced.
 *
 * Lives only inside the transaction [AssistantStore.write] opens, so a block of
 * writes lands together or not at all, and its [changes] are exactly what undo
 * needs to put back.
 */
class ChangeRecorder internal constructor(private val dao: NetworkDao) {
    private val recorded = mutableListOf<RowChange>()
    val changes: List<RowChange> get() = recorded.toList()

    suspend fun insertPerson(row: PersonEntity): PersonEntity =
        row.copy(id = dao.insertPerson(row)).also { record(null, RowImage.Person(it)) }

    suspend fun updatePerson(before: PersonEntity, after: PersonEntity) {
        dao.updatePerson(after)
        record(RowImage.Person(before), RowImage.Person(after))
    }

    suspend fun insertNote(row: InteractionEntity): InteractionEntity =
        row.copy(id = dao.insertInteraction(row)).also { record(null, RowImage.Note(it)) }

    suspend fun updateNote(before: InteractionEntity, after: InteractionEntity) {
        dao.updateInteraction(after)
        record(RowImage.Note(before), RowImage.Note(after))
    }

    suspend fun insertNeed(row: NeedEntity): NeedEntity =
        row.copy(id = dao.insertNeed(row)).also { record(null, RowImage.Need(it)) }

    suspend fun updateNeed(before: NeedEntity, after: NeedEntity) {
        dao.updateNeed(after)
        record(RowImage.Need(before), RowImage.Need(after))
    }

    suspend fun deleteNeed(row: NeedEntity) {
        dao.deleteNeed(row.id)
        record(RowImage.Need(row), null)
    }

    suspend fun insertCapability(row: CapabilityEntity): CapabilityEntity =
        row.copy(id = dao.insertCapability(row)).also { record(null, RowImage.Capability(it)) }

    suspend fun updateCapability(before: CapabilityEntity, after: CapabilityEntity) {
        dao.updateCapability(after)
        record(RowImage.Capability(before), RowImage.Capability(after))
    }

    suspend fun deleteCapability(row: CapabilityEntity) {
        dao.deleteCapability(row.id)
        record(RowImage.Capability(row), null)
    }

    suspend fun insertAffiliation(row: AffiliationEntity): AffiliationEntity =
        row.copy(id = dao.insertAffiliation(row)).also { record(null, RowImage.Affiliation(it)) }

    suspend fun updateAffiliation(before: AffiliationEntity, after: AffiliationEntity) {
        dao.saveAffiliation(after)
        record(RowImage.Affiliation(before), RowImage.Affiliation(after))
    }

    suspend fun deleteAffiliation(row: AffiliationEntity) {
        dao.deleteAffiliation(row.id)
        record(RowImage.Affiliation(row), null)
    }

    suspend fun insertFact(row: FactEntity): FactEntity =
        row.copy(id = dao.insertFact(row)).also { record(null, RowImage.Fact(it)) }

    suspend fun updateFact(before: FactEntity, after: FactEntity) {
        dao.updateFact(after)
        record(RowImage.Fact(before), RowImage.Fact(after))
    }

    suspend fun deleteFact(row: FactEntity) {
        dao.deleteFact(row.id)
        record(RowImage.Fact(row), null)
    }

    /**
     * Moves a note and every record citing it, recording each row that moved.
     *
     * The move itself is [NetworkDao.moveInteraction], the same one the person
     * screen uses, so a note moves the same way whoever moves it.
     */
    suspend fun moveNote(noteId: Long, toPersonId: Long, now: Long): MoveResult {
        val note = dao.interaction(noteId) ?: throw IllegalArgumentException("No note has id $noteId")
        val derived = buildList<RowImage> {
            dao.allNeeds().filter { it.sourceInteractionId == noteId }.forEach { add(RowImage.Need(it)) }
            dao.allCapabilities().filter { it.sourceInteractionId == noteId }.forEach { add(RowImage.Capability(it)) }
            dao.allAffiliations().filter { it.sourceInteractionId == noteId }.forEach { add(RowImage.Affiliation(it)) }
            dao.allFacts().filter { it.sourceInteractionId == noteId }.forEach { add(RowImage.Fact(it)) }
        }
        val result = dao.moveInteraction(noteId, MoveDestination.Existing(toPersonId), now)
        record(RowImage.Note(note), RowImage.Note(note.copy(personId = toPersonId)))
        derived.forEach { before ->
            val after = when (before) {
                is RowImage.Need -> RowImage.Need(before.row.copy(personId = toPersonId))
                is RowImage.Capability -> RowImage.Capability(before.row.copy(personId = toPersonId))
                is RowImage.Affiliation -> RowImage.Affiliation(before.row.copy(personId = toPersonId))
                is RowImage.Fact -> RowImage.Fact(before.row.copy(personId = toPersonId))
                else -> before
            }
            record(before, after)
        }
        return result
    }

    /** Bumps a person's updated time. Not recorded: undo never restores it. */
    suspend fun touch(personId: Long, now: Long) = dao.touchPerson(personId, now)

    private fun record(before: RowImage?, after: RowImage?) {
        recorded += RowChange(before, after)
    }
}

/**
 * The data operations behind the assistant's tools.
 *
 * Writes the assistant makes on its own go through [write], which records them
 * so a reply can be undone. Deleting and merging are separate methods, because
 * those only ever run after the user confirms them on a card.
 */
class AssistantStore(private val database: NetworkDatabase) {
    private val dao = database.networkDao()

    suspend fun snapshot(): NetworkSnapshot = NetworkSnapshot(
        people = dao.allPeople(),
        interactions = dao.allInteractions(),
        needs = dao.allNeeds(),
        capabilities = dao.allCapabilities(),
        affiliations = dao.allAffiliations(),
        facts = dao.allFacts(),
    )

    suspend fun person(id: Long): PersonEntity? = dao.person(id)
    suspend fun note(id: Long): InteractionEntity? = dao.interaction(id)
    suspend fun affiliation(id: Long): AffiliationEntity? = dao.affiliation(id)
    suspend fun need(id: Long): NeedEntity? = dao.need(id)
    suspend fun capability(id: Long): CapabilityEntity? = dao.capability(id)
    suspend fun fact(id: Long): FactEntity? = dao.fact(id)

    /** Runs [block] in one transaction and returns every row it changed. */
    suspend fun <T> write(block: suspend (ChangeRecorder) -> T): Recorded<T> = database.withTransaction {
        val recorder = ChangeRecorder(dao)
        val value = block(recorder)
        Recorded(value, recorder.changes)
    }

    /**
     * Puts every row [changes] touched back the way it was, all together.
     *
     * Refuses rather than guessing when anything was written since: a row that
     * no longer looks the way these changes left it, or a person or note these
     * changes created that has since gained notes or records of its own, which
     * removing it would take along.
     */
    suspend fun undo(changes: List<RowChange>, now: Long): Int = database.withTransaction {
        val first = LinkedHashMap<String, RowImage?>()
        val last = LinkedHashMap<String, RowImage?>()
        changes.forEach { change ->
            if (change.key !in first) first[change.key] = change.before
            last[change.key] = change.after
        }

        last.forEach { (key, expected) ->
            if (!sameContent(load(key), expected)) throw UndoConflictException(CHANGED_SINCE)
        }
        last.forEach { (key, image) ->
            if (first[key] != null || image == null) return@forEach
            when (image) {
                is RowImage.Person -> {
                    val accounted = last.values.count { it != null && it !is RowImage.Person && it.ownerId == image.row.id }
                    if (dao.countOwnedRows(image.row.id) > accounted) throw UndoConflictException(GAINED_SINCE)
                }
                is RowImage.Note -> {
                    val accounted = last.values.count { it?.sourceNoteId == image.row.id }
                    if (dao.countDerivedRows(image.row.id) > accounted) throw UndoConflictException(GAINED_SINCE)
                }
                else -> Unit
            }
        }

        // Parents go back in before children, and children come out before
        // parents, so no step ever breaks a foreign key.
        val keys = first.keys
        keys.filter { first[it] != null && last[it] == null }
            .sortedBy { rank(first.getValue(it)!!) }
            .forEach { insert(first.getValue(it)!!) }
        keys.filter { first[it] != null && last[it] != null }
            .forEach { restore(first.getValue(it)!!, now) }
        keys.filter { first[it] == null && last[it] != null }
            .sortedByDescending { rank(last.getValue(it)!!) }
            .forEach { delete(last.getValue(it)!!) }
        first.size
    }

    /** Deletes a person with every note and record. Returns their name. */
    suspend fun deletePerson(personId: Long): String {
        val person = dao.person(personId) ?: throw IllegalArgumentException("That person no longer exists")
        dao.deletePerson(person)
        return person.name
    }

    suspend fun deleteNote(noteId: Long) {
        val note = dao.interaction(noteId) ?: throw IllegalArgumentException("That note no longer exists")
        database.withTransaction {
            dao.deleteInteraction(note.id)
            dao.touchPerson(note.personId, System.currentTimeMillis())
        }
    }

    suspend fun deleteRecord(ref: RecordRef) {
        val personId = when (ref.kind) {
            RecordKind.POSITION, RecordKind.EDUCATION -> dao.affiliation(ref.id)?.personId
            RecordKind.NEED -> dao.need(ref.id)?.personId
            RecordKind.CAPABILITY -> dao.capability(ref.id)?.personId
            RecordKind.FACT -> dao.fact(ref.id)?.personId
        } ?: throw IllegalArgumentException("That record no longer exists")
        database.withTransaction {
            when (ref.kind) {
                RecordKind.POSITION, RecordKind.EDUCATION -> dao.deleteAffiliation(ref.id)
                RecordKind.NEED -> dao.deleteNeed(ref.id)
                RecordKind.CAPABILITY -> dao.deleteCapability(ref.id)
                RecordKind.FACT -> dao.deleteFact(ref.id)
            }
            dao.touchPerson(personId, System.currentTimeMillis())
        }
    }

    suspend fun mergePeople(keepId: Long, mergeId: Long, now: Long): MergeResult = dao.mergePeople(keepId, mergeId, now)

    private suspend fun load(key: String): RowImage? {
        val id = key.substringAfter(':').toLong()
        return when (key.substringBefore(':')) {
            "person" -> dao.person(id)?.let(RowImage::Person)
            "note" -> dao.interaction(id)?.let(RowImage::Note)
            "need" -> dao.need(id)?.let(RowImage::Need)
            "capability" -> dao.capability(id)?.let(RowImage::Capability)
            "affiliation" -> dao.affiliation(id)?.let(RowImage::Affiliation)
            "fact" -> dao.fact(id)?.let(RowImage::Fact)
            else -> throw IllegalArgumentException("Unknown row $key")
        }
    }

    /** Equal apart from a person's updated time, which any later write bumps. */
    private fun sameContent(current: RowImage?, expected: RowImage?): Boolean = when {
        current == null || expected == null -> current == expected
        current is RowImage.Person && expected is RowImage.Person ->
            current.row.copy(updatedAt = 0) == expected.row.copy(updatedAt = 0)
        else -> current == expected
    }

    private fun rank(image: RowImage): Int = when (image) {
        is RowImage.Person -> 0
        is RowImage.Note -> 1
        else -> 2
    }

    private suspend fun insert(image: RowImage) {
        when (image) {
            is RowImage.Person -> dao.insertPerson(image.row)
            is RowImage.Note -> dao.insertInteraction(image.row)
            is RowImage.Need -> dao.insertNeed(image.row)
            is RowImage.Capability -> dao.insertCapability(image.row)
            is RowImage.Affiliation -> dao.insertAffiliation(image.row)
            is RowImage.Fact -> dao.insertFact(image.row)
        }
    }

    private suspend fun restore(image: RowImage, now: Long) {
        when (image) {
            is RowImage.Person -> dao.updatePerson(image.row.copy(updatedAt = now))
            is RowImage.Note -> dao.updateInteraction(image.row)
            is RowImage.Need -> dao.updateNeed(image.row)
            is RowImage.Capability -> dao.updateCapability(image.row)
            is RowImage.Affiliation -> dao.saveAffiliation(image.row)
            is RowImage.Fact -> dao.updateFact(image.row)
        }
    }

    private suspend fun delete(image: RowImage) {
        when (image) {
            is RowImage.Person -> dao.deletePersonById(image.row.id)
            is RowImage.Note -> dao.deleteInteraction(image.row.id)
            is RowImage.Need -> dao.deleteNeed(image.row.id)
            is RowImage.Capability -> dao.deleteCapability(image.row.id)
            is RowImage.Affiliation -> dao.deleteAffiliation(image.row.id)
            is RowImage.Fact -> dao.deleteFact(image.row.id)
        }
    }

    private companion object {
        const val CHANGED_SINCE =
            "Some of these were edited afterwards, so undoing now would overwrite the newer version. Change them by hand on the person's page."
        const val GAINED_SINCE =
            "Something saved here has gained notes or records since, and undoing would remove those too. Change it by hand on the person's page."
    }
}
