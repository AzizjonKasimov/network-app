package com.azizjon.network.ai

import com.azizjon.network.data.AffiliationEntity
import com.azizjon.network.data.AssistantStore
import com.azizjon.network.data.CapabilityEntity
import com.azizjon.network.data.ChangeRecorder
import com.azizjon.network.data.FactEntity
import com.azizjon.network.data.InteractionEntity
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity
import com.azizjon.network.data.RecordKind
import com.azizjon.network.data.RecordRef
import com.azizjon.network.search.NetworkMatcher
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** What the phone sends back for one tool call. */
data class ToolResult(val output: String, val isError: Boolean)

/**
 * Runs the assistant's tool calls against the database on this phone.
 *
 * Reads return JSON the model can reason over. Writes land immediately through
 * [AssistantStore.write], which records them for undo, and each adds a readable
 * line to the turn's card. Deletes and merges never write: they queue a
 * [PendingAction] that only the user can carry out.
 *
 * Anything the phone refuses comes back as an error result the model can read
 * and correct, never as an exception out of [execute].
 */
class AssistantTools(
    private val store: AssistantStore,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** Runs before every write, so a backup is marked due even if the app dies mid-turn. */
    private val beforeWrite: () -> Unit = {},
) {
    fun definitions(): JSONArray = AssistantToolSchemas.definitions()

    suspend fun execute(name: String, input: JSONObject, log: AgentTurnLog): ToolResult {
        log.calls += describeCall(name, input)
        return try {
            val output = when (name) {
                "list_people" -> listPeople(input)
                "find_people" -> findPeople(input)
                "get_person" -> getPerson(input, log)
                "browse_records" -> browseRecords(input)
                "search_notes" -> searchNotes(input)
                "create_person" -> createPerson(input, log)
                "update_person" -> updatePerson(input, log)
                "add_note" -> addNote(input, log)
                "edit_note" -> editNote(input, log)
                "add_record" -> addRecord(input, log)
                "update_record" -> updateRecord(input, log)
                "change_record_kind" -> changeRecordKind(input, log)
                "move_note" -> moveNote(input, log)
                "delete_person" -> queueDeletePerson(input, log)
                "delete_note" -> queueDeleteNote(input, log)
                "delete_record" -> queueDeleteRecord(input, log)
                "merge_people" -> queueMerge(input, log)
                else -> throw ToolFailure("There is no tool called $name.")
            }
            ToolResult(output.toString(), isError = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ToolFailure) {
            ToolResult(failure.message ?: "That did not work.", isError = true)
        } catch (failure: IllegalArgumentException) {
            // The data layer's own checks, such as moving a note onto an archived person.
            ToolResult(failure.message ?: "That did not work.", isError = true)
        } catch (failure: Exception) {
            ToolResult("The phone could not do that: ${failure.message ?: failure.javaClass.simpleName}", isError = true)
        }
    }

    /** A few words for the thread while a call runs. */
    fun progressLabel(name: String, input: JSONObject): String = when (name) {
        "list_people", "browse_records" -> "Looking through your network…"
        "find_people" -> input.optString("query").trim().take(40)
            .let { if (it.isEmpty()) "Looking someone up…" else "Looking for $it…" }
        "get_person" -> "Reading a profile…"
        "search_notes" -> "Searching your notes…"
        "create_person" -> input.optString("name").trim().take(40)
            .let { if (it.isEmpty()) "Adding someone…" else "Adding $it…" }
        "move_note" -> "Moving a note…"
        in AssistantToolSchemas.CONFIRMATION_TOOLS -> "Preparing a confirmation…"
        else -> "Saving changes…"
    }

    // ---- reading ----

    private suspend fun listPeople(input: JSONObject): JSONObject {
        val includeArchived = input.optionalBoolean("include_archived") ?: false
        val index = Index(store.snapshot())
        val people = index.snapshot.people
            .filter { includeArchived || !it.archived }
            .sortedWith(compareByDescending<PersonEntity> { it.isSelf }.thenBy { it.name.lowercase() })
        val (rows, next) = page(people, offset(input)) { brief(index, it) }
        return JSONObject().put("total", people.size).put("people", rows).putOpt("next_offset", next)
    }

    private suspend fun findPeople(input: JSONObject): JSONObject {
        val query = input.requiredText("query", 200)
        val includeArchived = input.optionalBoolean("include_archived") ?: false
        val index = Index(store.snapshot())
        val zone = zone()
        val matches = JSONArray()
        val seen = mutableSetOf<Long>()

        val byName = PersonResolver.resolve(index.snapshot.people, query, includeArchived)
        (listOfNotNull(byName.exact) + byName.suggestions).forEach { person ->
            if (matches.length() < MAX_MATCHES && seen.add(person.id)) {
                matches.put(brief(index, person).put("matched", JSONArray().put(JSONObject().put("kind", "Name").put("text", person.name))))
            }
        }
        NetworkMatcher.search(index.snapshot, query).forEach { result ->
            if (matches.length() < MAX_MATCHES && seen.add(result.person.id)) {
                val evidence = result.evidence.take(3).map { item ->
                    JSONObject().put("kind", item.kind).put("text", item.text.take(200)).put("date", formatToolDate(item.recordedAt, zone))
                }
                matches.put(brief(index, result.person).put("matched", JSONArray(evidence)))
            }
        }
        return JSONObject().put("matches", matches).apply {
            if (matches.length() == 0) put("hint", "Nobody saved matches those words. Try other words, list_people, or browse_records.")
        }
    }

    private suspend fun getPerson(input: JSONObject, log: AgentTurnLog): JSONObject {
        val personId = input.requiredLong("person_id")
        val limit = (input.optionalLong("notes_limit") ?: DEFAULT_NOTES).coerceIn(1L, 100L).toInt()
        val index = Index(store.snapshot())
        val person = index.person(personId)
        log.people += person.id
        val zone = zone()
        val (education, positions) = index.affiliations[person.id].orEmpty()
            .sortedWith(compareByDescending<AffiliationEntity> { it.current }.thenByDescending { it.lastConfirmedAt })
            .partition { it.isEducation }
        val notes = index.notes[person.id].orEmpty()
            .sortedWith(compareByDescending<InteractionEntity> { it.occurredAt }.thenByDescending { it.id })

        return JSONObject()
            .put("id", person.id)
            .put("name", person.name)
            .apply {
                if (person.isSelf) put("is_user", true)
                if (person.archived) put("archived", true)
            }
            .put("location", person.location)
            .put("relationship", person.relationship)
            .put("tags", person.tags)
            .put("profile_notes", person.notes)
            .put("has_contact", person.contact.isNotBlank())
            .put("positions", JSONArray(positions.map { affiliationJson(it, zone) }))
            .put("education", JSONArray(education.map { affiliationJson(it, zone) }))
            .put(
                "needs",
                JSONArray(index.needs[person.id].orEmpty().sortedByDescending { it.lastConfirmedAt }.map { need ->
                    recordJson(RecordRef.of(need), need.text, need.status == NeedEntity.STATUS_ACTIVE, need.lastConfirmedAt, need.sourceInteractionId, zone)
                }),
            )
            .put(
                "capabilities",
                JSONArray(index.capabilities[person.id].orEmpty().sortedByDescending { it.lastConfirmedAt }.map { capability ->
                    recordJson(RecordRef.of(capability), capability.text, capability.active, capability.lastConfirmedAt, capability.sourceInteractionId, zone)
                }),
            )
            .put(
                "background",
                JSONArray(index.facts[person.id].orEmpty().sortedByDescending { it.lastConfirmedAt }.map { fact ->
                    JSONObject()
                        .put("ref", RecordRef.of(fact).toString())
                        .put("text", fact.text)
                        .put("confirmed", formatToolDate(fact.lastConfirmedAt, zone))
                        .putOpt("source_note_id", fact.sourceInteractionId)
                }),
            )
            .put("notes_total", notes.size)
            .put(
                "notes",
                JSONArray(notes.take(limit).map { note ->
                    JSONObject().put("id", note.id).put("date", formatToolDate(note.occurredAt, zone)).put("text", note.note)
                }),
            )
    }

    private suspend fun browseRecords(input: JSONObject): JSONObject {
        val kinds = input.optionalStrings("kinds")
            ?.map { RecordKind.parse(it) ?: throw ToolFailure("$it is not a record kind.") }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: RecordKind.entries.toSet()
        val includeInactive = input.optionalBoolean("include_inactive") ?: false
        val index = Index(store.snapshot())
        val zone = zone()
        val visible = index.snapshot.people.filter { includeInactive || !it.archived }.map { it.id }.toSet()

        val records = buildList {
            index.snapshot.affiliations.forEach { add(Listed(RecordRef.of(it), it.personId, it.label, it.current, it.lastConfirmedAt)) }
            index.snapshot.needs.forEach { add(Listed(RecordRef.of(it), it.personId, it.text, it.status == NeedEntity.STATUS_ACTIVE, it.lastConfirmedAt)) }
            index.snapshot.capabilities.forEach { add(Listed(RecordRef.of(it), it.personId, it.text, it.active, it.lastConfirmedAt)) }
            index.snapshot.facts.forEach { add(Listed(RecordRef.of(it), it.personId, it.text, true, it.lastConfirmedAt)) }
        }
            .filter { it.ref.kind in kinds && it.personId in visible }
            // Study is mostly in the past and still worth knowing, so only
            // closed needs, inactive capabilities, and past jobs are hidden.
            .filter { includeInactive || it.active || it.ref.kind == RecordKind.EDUCATION }
            .sortedByDescending { it.date }

        val (rows, next) = page(records, offset(input)) { record ->
            JSONObject()
                .put("ref", record.ref.toString())
                .put("person_id", record.personId)
                .put("person", index.people[record.personId]?.name.orEmpty())
                .put("text", record.text)
                .apply { if (!record.active) put(if (record.ref.kind.isAffiliation) "current" else "active", false) }
                .put("confirmed", formatToolDate(record.date, zone))
        }
        return JSONObject().put("total", records.size).put("records", rows).putOpt("next_offset", next)
    }

    private suspend fun searchNotes(input: JSONObject): JSONObject {
        val zone = zone()
        val query = input.optionalText("query", 200).orEmpty()
        val personId = input.optionalLong("person_id")
        val from = parseRangeDate(input.optionalText("from", 40), zone, endOfDay = false)
        val to = parseRangeDate(input.optionalText("to", 40), zone, endOfDay = true)
        val tokens = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.distinct()
        val index = Index(store.snapshot())
        if (personId != null) index.person(personId)

        val notes = index.snapshot.interactions.asSequence()
            .filter { personId == null || it.personId == personId }
            .filter { from == null || it.occurredAt >= from }
            .filter { to == null || it.occurredAt <= to }
            .map { note -> note to tokens.count { token -> note.note.lowercase().contains(token) } }
            .filter { tokens.isEmpty() || it.second > 0 }
            .sortedWith(compareByDescending<Pair<InteractionEntity, Int>> { it.second }.thenByDescending { it.first.occurredAt })
            .map { it.first }
            .toList()

        val (rows, next) = page(notes, offset(input)) { note ->
            JSONObject()
                .put("id", note.id)
                .put("person_id", note.personId)
                .put("person", index.people[note.personId]?.name.orEmpty())
                .put("date", formatToolDate(note.occurredAt, zone))
                .put("text", note.note.take(MAX_NOTE_EXCERPT))
        }
        return JSONObject().put("total", notes.size).put("notes", rows).putOpt("next_offset", next)
    }

    // ---- writing ----

    private suspend fun createPerson(input: JSONObject, log: AgentTurnLog): JSONObject {
        val name = input.requiredText("name", 200)
        val allowSameName = input.optionalBoolean("allow_same_name") ?: false
        val existing = store.snapshot().people.filter { it.name.trim().equals(name, ignoreCase = true) }
        if (existing.isNotEmpty() && !allowSameName) {
            throw ToolFailure(
                "$name is already saved as person ${existing.joinToString { it.id.toString() }}. " +
                    "Use that person, or set allow_same_name if the user said this is somebody else.",
            )
        }
        val now = clock().toEpochMilli()
        val person = write(log) { recorder ->
            recorder.insertPerson(
                PersonEntity(
                    name = name,
                    location = input.optionalText("location", 500).orEmpty(),
                    contact = input.optionalText("contact", 500).orEmpty(),
                    relationship = input.optionalText("relationship", 500).orEmpty(),
                    tags = input.optionalText("tags", 500).orEmpty(),
                    notes = input.optionalText("profile_notes", 4_000).orEmpty(),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        log.people += person.id
        log.saved += "Added $name"
        log.memory += "created person ${person.id} ($name)"
        return JSONObject().put("person_id", person.id).put("name", name)
    }

    private suspend fun updatePerson(input: JSONObject, log: AgentTurnLog): JSONObject {
        val personId = input.requiredLong("person_id")
        val before = store.person(personId) ?: throw noPerson(personId)
        var after = before
        val fields = mutableListOf<String>()

        input.optionalText("name", 200)?.let { name ->
            if (name.isEmpty()) throw ToolFailure("A name cannot be empty.")
            if (name != before.name) after = after.copy(name = name)
        }
        input.optionalText("location", 500)?.let { if (it != before.location) { after = after.copy(location = it); fields += "location" } }
        input.optionalText("relationship", 500)?.let { if (it != before.relationship) { after = after.copy(relationship = it); fields += "relationship" } }
        input.optionalText("tags", 500)?.let { if (it != before.tags) { after = after.copy(tags = it); fields += "tags" } }
        input.optionalText("profile_notes", 4_000)?.let { if (it != before.notes) { after = after.copy(notes = it); fields += "profile notes" } }
        input.optionalText("contact", 500)?.let { if (it != before.contact) { after = after.copy(contact = it); fields += "contact" } }
        input.optionalBoolean("archived")?.let { if (it != before.archived) after = after.copy(archived = it) }
        if (after == before) throw ToolFailure("Nothing would change: ${before.name}'s profile already says that.")

        val now = clock().toEpochMilli()
        write(log) { it.updatePerson(before, after.copy(updatedAt = now)) }
        log.people += personId
        if (after.name != before.name) log.saved += "Renamed ${before.name} to ${after.name}"
        if (fields.isNotEmpty()) log.saved += "Updated ${after.name}'s ${fields.joinToEnglish()}"
        if (after.archived != before.archived) {
            log.saved += if (after.archived) "Archived ${after.name}" else "Brought ${after.name} back from the archive"
        }
        val changed = buildList {
            if (after.name != before.name) add("name")
            addAll(fields)
            if (after.archived != before.archived) add("archived")
        }
        log.memory += "updated person $personId (${after.name}): ${changed.joinToString()}"
        return JSONObject().put("person_id", personId).put("changed", JSONArray(changed))
    }

    /**
     * Saves a note and, in the same transaction, the records it supports.
     *
     * Taking the records here rather than only through add_record saves a
     * whole model step on every capture: a record has to cite the note, and a
     * separate call could not know the note's id until this one returned.
     */
    private suspend fun addNote(input: JSONObject, log: AgentTurnLog): JSONObject {
        val personId = input.requiredLong("person_id")
        val person = store.person(personId) ?: throw noPerson(personId)
        val text = input.requiredText("text", 4_000)
        val now = clock()
        val occurredAt = parseToolDate(input.optionalText("date", 40), zone(), now) ?: now.toEpochMilli()

        // A record that cannot be saved is reported back rather than failing the
        // note: the usual reason is that the person already has it.
        val snapshot = store.snapshot()
        val accepted = mutableListOf<NewRecord>()
        val skipped = JSONArray()
        val requested = input.optJSONArray("records") ?: JSONArray()
        if (requested.length() > MAX_RECORDS_PER_NOTE) throw ToolFailure("Pass at most $MAX_RECORDS_PER_NOTE records with one note.")
        for (index in 0 until requested.length()) {
            val item = requested.optJSONObject(index) ?: throw ToolFailure("records[$index] must be an object.")
            try {
                val record = readNewRecord(item, now, defaultConfirmed = occurredAt)
                checkNotDuplicate(snapshot, person, record)
                if (accepted.any { it.sameAs(record) }) throw ToolFailure("It is listed twice in this call.")
                accepted += record
            } catch (failure: ToolFailure) {
                skipped.put(JSONObject().put("index", index).put("reason", failure.message))
            }
        }

        val (note, refs) = write(log) { recorder ->
            val saved = recorder.insertNote(
                InteractionEntity(
                    personId = personId,
                    note = text,
                    occurredAt = occurredAt,
                    createdAt = now.toEpochMilli(),
                    origin = InteractionEntity.ORIGIN_ASSISTANT,
                ),
            )
            val created = accepted.map { record -> record to insertRecord(recorder, personId, record, now.toEpochMilli(), saved.id) }
            recorder.touch(personId, now.toEpochMilli())
            saved to created
        }
        log.people += personId
        log.saved += "Saved a note on ${person.name}"
        log.memory += "added note ${note.id} to ${person.name} (person $personId)"
        refs.forEach { (record, ref) ->
            log.saved += "Added ${record.kind.withArticle()} for ${person.name}: ${record.display}"
            log.memory += "added $ref to ${person.name} (person $personId)"
        }
        return JSONObject()
            .put("note_id", note.id)
            .put("records", JSONArray(refs.map { it.second.toString() }))
            .apply { if (skipped.length() > 0) put("skipped", skipped) }
    }

    private suspend fun editNote(input: JSONObject, log: AgentTurnLog): JSONObject {
        val noteId = input.requiredLong("note_id")
        val before = store.note(noteId) ?: throw noNote(noteId)
        val text = input.optionalText("text", 4_000)
        if (text != null && text.isEmpty()) throw ToolFailure("A note cannot be empty.")
        val now = clock()
        val date = parseToolDate(input.optionalText("date", 40), zone(), now)
        val after = before.copy(note = text ?: before.note, occurredAt = date ?: before.occurredAt)
        if (after == before) throw ToolFailure("Nothing would change: note $noteId already says that.")
        write(log) { recorder ->
            recorder.updateNote(before, after)
            recorder.touch(before.personId, now.toEpochMilli())
        }
        val name = store.person(before.personId)?.name.orEmpty()
        log.people += before.personId
        log.saved += "Edited a note on $name"
        log.memory += "edited note $noteId on $name (person ${before.personId})"
        return JSONObject().put("note_id", noteId)
    }

    private suspend fun addRecord(input: JSONObject, log: AgentTurnLog): JSONObject {
        val personId = input.requiredLong("person_id")
        val person = store.person(personId) ?: throw noPerson(personId)
        val now = clock()
        val record = readNewRecord(input, now, defaultConfirmed = now.toEpochMilli())
        val sourceNoteId = input.optionalLong("source_note_id")?.also { noteId ->
            val note = store.note(noteId) ?: throw noNote(noteId)
            if (note.personId != personId) {
                throw ToolFailure("Note $noteId is on somebody else. source_note_id must be a note on ${person.name}.")
            }
        }
        checkNotDuplicate(store.snapshot(), person, record)

        val ref = write(log) { recorder ->
            insertRecord(recorder, personId, record, now.toEpochMilli(), sourceNoteId)
                .also { recorder.touch(personId, now.toEpochMilli()) }
        }
        log.people += personId
        log.saved += "Added ${record.kind.withArticle()} for ${person.name}: ${record.display}"
        log.memory += "added $ref to ${person.name} (person $personId)"
        return JSONObject().put("record", ref.toString())
    }

    /** A record the assistant asked to add, checked but not yet written. */
    private data class NewRecord(
        val kind: RecordKind,
        val text: String,
        val organization: String,
        val role: String,
        val current: Boolean,
        val confirmed: Long,
    ) {
        /** How the card shows it. */
        val display: String
            get() = if (kind.isAffiliation) {
                listOf(role, organization).filter(String::isNotEmpty).joinToString(" at ") + if (current) "" else " (past)"
            } else {
                text
            }

        fun sameAs(other: NewRecord): Boolean = kind == other.kind &&
            normalizeText(text) == normalizeText(other.text) &&
            organization.equals(other.organization, ignoreCase = true) &&
            role.equals(other.role, ignoreCase = true)
    }

    private fun readNewRecord(input: JSONObject, now: Instant, defaultConfirmed: Long): NewRecord {
        val kind = RecordKind.parse(input.requiredText("kind", 20))
            ?: throw ToolFailure("kind must be position, education, need, capability, or fact.")
        val confirmed = parseToolDate(input.optionalText("date", 40), zone(), now) ?: defaultConfirmed
        if (!kind.isAffiliation) {
            return NewRecord(kind, input.requiredText("text", 1_000), "", "", current = true, confirmed = confirmed)
        }
        val organization = input.optionalText("organization", 500).orEmpty()
        val role = input.optionalText("role", 500).orEmpty()
        if (organization.isEmpty() && role.isEmpty()) {
            throw ToolFailure("${kind.withArticle().replaceFirstChar(Char::uppercase)} needs an organization or a role.")
        }
        return NewRecord(kind, "", organization, role, input.optionalBoolean("current") ?: true, confirmed)
    }

    private fun checkNotDuplicate(snapshot: NetworkSnapshot, person: PersonEntity, record: NewRecord) {
        when (record.kind) {
            RecordKind.POSITION, RecordKind.EDUCATION -> snapshot.affiliationsFor(person.id)
                .firstOrNull {
                    it.isEducation == (record.kind == RecordKind.EDUCATION) &&
                        it.organization.equals(record.organization, ignoreCase = true) &&
                        it.role.equals(record.role, ignoreCase = true)
                }
                ?.let { throw duplicate(person, RecordRef.of(it), it.label) }
            RecordKind.NEED -> snapshot.needsFor(person.id).firstOrNull { sameText(it.text, record.text) }
                ?.let { throw duplicate(person, RecordRef.of(it), it.text) }
            RecordKind.CAPABILITY -> snapshot.capabilitiesFor(person.id).firstOrNull { sameText(it.text, record.text) }
                ?.let { throw duplicate(person, RecordRef.of(it), it.text) }
            RecordKind.FACT -> snapshot.factsFor(person.id).firstOrNull { sameText(it.text, record.text) }
                ?.let { throw duplicate(person, RecordRef.of(it), it.text) }
        }
    }

    private suspend fun insertRecord(
        recorder: ChangeRecorder,
        personId: Long,
        record: NewRecord,
        now: Long,
        sourceNoteId: Long?,
    ): RecordRef = when (record.kind) {
        RecordKind.POSITION, RecordKind.EDUCATION -> RecordRef.of(
            recorder.insertAffiliation(
                AffiliationEntity(
                    personId = personId,
                    organization = record.organization,
                    role = record.role,
                    current = record.current,
                    lastConfirmedAt = record.confirmed,
                    createdAt = now,
                    sourceInteractionId = sourceNoteId,
                    kind = if (record.kind == RecordKind.EDUCATION) AffiliationEntity.KIND_EDUCATION else AffiliationEntity.KIND_WORK,
                ),
            ),
        )
        RecordKind.NEED -> RecordRef.of(
            recorder.insertNeed(
                NeedEntity(personId = personId, text = record.text, lastConfirmedAt = record.confirmed, createdAt = now, sourceInteractionId = sourceNoteId),
            ),
        )
        RecordKind.CAPABILITY -> RecordRef.of(
            recorder.insertCapability(
                CapabilityEntity(personId = personId, text = record.text, lastConfirmedAt = record.confirmed, createdAt = now, sourceInteractionId = sourceNoteId),
            ),
        )
        RecordKind.FACT -> RecordRef.of(
            recorder.insertFact(
                FactEntity(personId = personId, text = record.text, lastConfirmedAt = record.confirmed, createdAt = now, sourceInteractionId = sourceNoteId),
            ),
        )
    }

    private suspend fun updateRecord(input: JSONObject, log: AgentTurnLog): JSONObject {
        val ref = parseRef(input.requiredText("record", 40))
        val now = clock()
        val nowMillis = now.toEpochMilli()
        val confirmed = parseToolDate(input.optionalText("date", 40), zone(), now)
        // "active" and "current" mean the same thing to a person reading them;
        // accept either on any kind rather than failing on the wrong word.
        val stillTrue = input.optionalBoolean("active") ?: input.optionalBoolean("current")

        val (personId, line) = when (ref.kind) {
            RecordKind.POSITION, RecordKind.EDUCATION -> {
                val before = store.affiliation(ref.id) ?: throw noRecord(ref)
                val after = before.copy(
                    organization = input.optionalText("organization", 500) ?: before.organization,
                    role = input.optionalText("role", 500) ?: before.role,
                    current = stillTrue ?: before.current,
                    lastConfirmedAt = confirmed ?: before.lastConfirmedAt,
                )
                if (after.organization.isEmpty() && after.role.isEmpty()) throw ToolFailure("It needs an organization or a role.")
                if (after == before) throw nothingToChange(ref)
                write(log) { recorder ->
                    recorder.updateAffiliation(before, after)
                    recorder.touch(before.personId, nowMillis)
                }
                val label = if (after.isEducation) "education" else "position"
                before.personId to when {
                    after.current != before.current && after.organization == before.organization && after.role == before.role ->
                        "Marked ${nameOf(before.personId)}'s $label as ${if (after.current) "current" else "past"}: ${after.label}"
                    else -> "Updated ${nameOf(before.personId)}'s $label: ${after.label}"
                }
            }
            RecordKind.NEED -> {
                val before = store.need(ref.id) ?: throw noRecord(ref)
                val after = before.copy(
                    text = textOrKeep(input, before.text),
                    status = stillTrue?.let { if (it) NeedEntity.STATUS_ACTIVE else NeedEntity.STATUS_CLOSED } ?: before.status,
                    lastConfirmedAt = confirmed ?: before.lastConfirmedAt,
                )
                if (after == before) throw nothingToChange(ref)
                write(log) { recorder ->
                    recorder.updateNeed(before, after)
                    recorder.touch(before.personId, nowMillis)
                }
                before.personId to when {
                    after.status != before.status && after.text == before.text ->
                        (if (after.status == NeedEntity.STATUS_CLOSED) "Closed" else "Reopened") + " ${nameOf(before.personId)}'s need: ${after.text}"
                    else -> "Updated ${nameOf(before.personId)}'s need: ${after.text}"
                }
            }
            RecordKind.CAPABILITY -> {
                val before = store.capability(ref.id) ?: throw noRecord(ref)
                val after = before.copy(
                    text = textOrKeep(input, before.text),
                    active = stillTrue ?: before.active,
                    lastConfirmedAt = confirmed ?: before.lastConfirmedAt,
                )
                if (after == before) throw nothingToChange(ref)
                write(log) { recorder ->
                    recorder.updateCapability(before, after)
                    recorder.touch(before.personId, nowMillis)
                }
                before.personId to when {
                    after.active != before.active && after.text == before.text ->
                        "Marked ${nameOf(before.personId)}'s capability as ${if (after.active) "active" else "inactive"}: ${after.text}"
                    else -> "Updated ${nameOf(before.personId)}'s capability: ${after.text}"
                }
            }
            RecordKind.FACT -> {
                val before = store.fact(ref.id) ?: throw noRecord(ref)
                val after = before.copy(text = textOrKeep(input, before.text), lastConfirmedAt = confirmed ?: before.lastConfirmedAt)
                if (after == before) throw nothingToChange(ref)
                write(log) { recorder ->
                    recorder.updateFact(before, after)
                    recorder.touch(before.personId, nowMillis)
                }
                before.personId to "Updated ${nameOf(before.personId)}'s background: ${after.text}"
            }
        }
        log.people += personId
        log.saved += line
        log.memory += "updated $ref (person $personId)"
        return JSONObject().put("record", ref.toString())
    }

    private suspend fun changeRecordKind(input: JSONObject, log: AgentTurnLog): JSONObject {
        val ref = parseRef(input.requiredText("record", 40))
        val newKind = RecordKind.parse(input.requiredText("new_kind", 20))
            ?: throw ToolFailure("new_kind must be position, education, need, capability, or fact.")
        val source = loadSource(ref)
        // Compare against what the row really is: position:5 and education:5
        // reach the same affiliation, whichever prefix the model used.
        if (newKind == source.ref.kind) throw ToolFailure("${source.ref} is already ${newKind.withArticle()}.")
        val text = input.optionalText("text", 1_000)?.takeIf(String::isNotEmpty) ?: source.text
        val organization = input.optionalText("organization", 500)
        val role = input.optionalText("role", 500)
        if (newKind.isAffiliation && source.affiliation == null && organization.isNullOrEmpty() && role.isNullOrEmpty()) {
            throw ToolFailure("Turning it into ${newKind.withArticle()} needs an organization or a role.")
        }
        val now = clock().toEpochMilli()
        val person = store.person(source.personId) ?: throw noPerson(source.personId)

        val newRef = write(log) { recorder ->
            val affiliation = source.affiliation
            val created = if (affiliation != null && newKind.isAffiliation) {
                val after = affiliation.copy(
                    kind = if (newKind == RecordKind.EDUCATION) AffiliationEntity.KIND_EDUCATION else AffiliationEntity.KIND_WORK,
                    organization = organization ?: affiliation.organization,
                    role = role ?: affiliation.role,
                )
                recorder.updateAffiliation(affiliation, after)
                RecordRef.of(after)
            } else {
                source.delete(recorder)
                insertAs(recorder, newKind, source, text, organization.orEmpty(), role.orEmpty())
            }
            recorder.touch(source.personId, now)
            created
        }
        log.people += source.personId
        log.saved += "Changed ${person.name}'s ${source.ref.kind.label} into ${newKind.withArticle()}: " +
            if (newKind.isAffiliation) listOf(role ?: source.affiliation?.role.orEmpty(), organization ?: source.affiliation?.organization.orEmpty())
                .filter(String::isNotEmpty).joinToString(" at ") else text
        log.memory += "changed $ref into $newRef (person ${source.personId})"
        return JSONObject().put("record", newRef.toString())
    }

    private suspend fun moveNote(input: JSONObject, log: AgentTurnLog): JSONObject {
        val noteId = input.requiredLong("note_id")
        val toPersonId = input.requiredLong("to_person_id")
        val note = store.note(noteId) ?: throw noNote(noteId)
        val from = store.person(note.personId) ?: throw noPerson(note.personId)
        val to = store.person(toPersonId) ?: throw noPerson(toPersonId)
        if (from.id == to.id) throw ToolFailure("Note $noteId is already on ${to.name}.")
        val result = write(log) { it.moveNote(noteId, toPersonId, clock().toEpochMilli()) }
        log.people += from.id
        log.people += to.id
        log.saved += "Moved a note from ${from.name} to ${to.name}" + when (result.records) {
            0 -> ""
            1 -> " with the record that came from it"
            else -> " with the ${result.records} records that came from it"
        }
        log.memory += "moved note $noteId and ${result.records} records from person ${from.id} to person ${to.id}"
        return JSONObject().put("note_id", noteId).put("to_person_id", toPersonId).put("records_moved", result.records)
    }

    // ---- asking first ----

    private suspend fun queueDeletePerson(input: JSONObject, log: AgentTurnLog): JSONObject {
        val personId = input.requiredLong("person_id")
        val snapshot = store.snapshot()
        val person = snapshot.person(personId) ?: throw noPerson(personId)
        val owned = snapshot.interactionsFor(personId).size + snapshot.needsFor(personId).size +
            snapshot.capabilitiesFor(personId).size + snapshot.affiliationsFor(personId).size + snapshot.factsFor(personId).size
        val action = log.pending.filterIsInstance<PendingAction.DeletePerson>().firstOrNull { it.personId == personId }
            ?: PendingAction.DeletePerson(nextActionId(log), personId, person.name, owned).also { log.pending += it }
        log.people += personId
        return waiting(action)
    }

    private suspend fun queueDeleteNote(input: JSONObject, log: AgentTurnLog): JSONObject {
        val noteId = input.requiredLong("note_id")
        val note = store.note(noteId) ?: throw noNote(noteId)
        val action = log.pending.filterIsInstance<PendingAction.DeleteNote>().firstOrNull { it.noteId == noteId }
            ?: PendingAction.DeleteNote(
                id = nextActionId(log),
                noteId = noteId,
                personName = nameOf(note.personId),
                date = formatToolDate(note.occurredAt, zone()),
                preview = note.note.replace(Regex("\\s+"), " ").trim().let { if (it.length > 80) it.take(79) + "…" else it },
            ).also { log.pending += it }
        log.people += note.personId
        return waiting(action)
    }

    private suspend fun queueDeleteRecord(input: JSONObject, log: AgentTurnLog): JSONObject {
        val ref = parseRef(input.requiredText("record", 40))
        val source = loadSource(ref)
        val canonical = source.ref
        val action = log.pending.filterIsInstance<PendingAction.DeleteRecord>().firstOrNull { it.ref == canonical }
            ?: PendingAction.DeleteRecord(nextActionId(log), canonical, nameOf(source.personId), source.text)
                .also { log.pending += it }
        log.people += source.personId
        return waiting(action)
    }

    private suspend fun queueMerge(input: JSONObject, log: AgentTurnLog): JSONObject {
        val keepId = input.requiredLong("keep_person_id")
        val mergeId = input.requiredLong("merge_person_id")
        if (keepId == mergeId) throw ToolFailure("keep_person_id and merge_person_id are the same person.")
        val keep = store.person(keepId) ?: throw noPerson(keepId)
        val merge = store.person(mergeId) ?: throw noPerson(mergeId)
        val action = log.pending.filterIsInstance<PendingAction.MergePeople>().firstOrNull { it.keepId == keepId && it.mergeId == mergeId }
            ?: PendingAction.MergePeople(nextActionId(log), keepId, keep.name, mergeId, merge.name).also { log.pending += it }
        log.people += keepId
        log.people += mergeId
        return waiting(action)
    }

    // ---- helpers ----

    private suspend fun <T> write(log: AgentTurnLog, block: suspend (ChangeRecorder) -> T): T {
        beforeWrite()
        val recorded = store.write(block)
        log.changes += recorded.changes
        return recorded.value
    }

    /** One record read into a shape every kind fits, for converting or describing it. */
    private class Source(
        val ref: RecordRef,
        val personId: Long,
        val text: String,
        val active: Boolean,
        val confirmed: Long,
        val created: Long,
        val sourceNoteId: Long?,
        val affiliation: AffiliationEntity? = null,
        val delete: suspend (ChangeRecorder) -> Unit,
    )

    private suspend fun loadSource(ref: RecordRef): Source = when (ref.kind) {
        RecordKind.POSITION, RecordKind.EDUCATION -> {
            val row = store.affiliation(ref.id) ?: throw noRecord(ref)
            Source(RecordRef.of(row), row.personId, row.label, row.current, row.lastConfirmedAt, row.createdAt, row.sourceInteractionId, row) {
                it.deleteAffiliation(row)
            }
        }
        RecordKind.NEED -> {
            val row = store.need(ref.id) ?: throw noRecord(ref)
            Source(ref, row.personId, row.text, row.status == NeedEntity.STATUS_ACTIVE, row.lastConfirmedAt, row.createdAt, row.sourceInteractionId) {
                it.deleteNeed(row)
            }
        }
        RecordKind.CAPABILITY -> {
            val row = store.capability(ref.id) ?: throw noRecord(ref)
            Source(ref, row.personId, row.text, row.active, row.lastConfirmedAt, row.createdAt, row.sourceInteractionId) {
                it.deleteCapability(row)
            }
        }
        RecordKind.FACT -> {
            val row = store.fact(ref.id) ?: throw noRecord(ref)
            Source(ref, row.personId, row.text, true, row.lastConfirmedAt, row.createdAt, row.sourceInteractionId) {
                it.deleteFact(row)
            }
        }
    }

    private suspend fun insertAs(
        recorder: ChangeRecorder,
        kind: RecordKind,
        source: Source,
        text: String,
        organization: String,
        role: String,
    ): RecordRef = when (kind) {
        RecordKind.POSITION, RecordKind.EDUCATION -> RecordRef.of(
            recorder.insertAffiliation(
                AffiliationEntity(
                    personId = source.personId,
                    organization = organization,
                    role = role,
                    current = source.active,
                    lastConfirmedAt = source.confirmed,
                    createdAt = source.created,
                    sourceInteractionId = source.sourceNoteId,
                    kind = if (kind == RecordKind.EDUCATION) AffiliationEntity.KIND_EDUCATION else AffiliationEntity.KIND_WORK,
                ),
            ),
        )
        RecordKind.NEED -> RecordRef.of(
            recorder.insertNeed(
                NeedEntity(
                    personId = source.personId,
                    text = text,
                    status = if (source.active) NeedEntity.STATUS_ACTIVE else NeedEntity.STATUS_CLOSED,
                    lastConfirmedAt = source.confirmed,
                    createdAt = source.created,
                    sourceInteractionId = source.sourceNoteId,
                ),
            ),
        )
        RecordKind.CAPABILITY -> RecordRef.of(
            recorder.insertCapability(
                CapabilityEntity(
                    personId = source.personId,
                    text = text,
                    active = source.active,
                    lastConfirmedAt = source.confirmed,
                    createdAt = source.created,
                    sourceInteractionId = source.sourceNoteId,
                ),
            ),
        )
        RecordKind.FACT -> RecordRef.of(
            recorder.insertFact(
                FactEntity(
                    personId = source.personId,
                    text = text,
                    lastConfirmedAt = source.confirmed,
                    createdAt = source.created,
                    sourceInteractionId = source.sourceNoteId,
                ),
            ),
        )
    }

    private class Index(val snapshot: NetworkSnapshot) {
        val people = snapshot.people.associateBy { it.id }
        val notes = snapshot.interactions.groupBy { it.personId }
        val needs = snapshot.needs.groupBy { it.personId }
        val capabilities = snapshot.capabilities.groupBy { it.personId }
        val affiliations = snapshot.affiliations.groupBy { it.personId }
        val facts = snapshot.facts.groupBy { it.personId }

        fun person(id: Long): PersonEntity = people[id] ?: throw noPerson(id)
    }

    private data class Listed(val ref: RecordRef, val personId: Long, val text: String, val active: Boolean, val date: Long)

    private fun brief(index: Index, person: PersonEntity): JSONObject {
        val zone = zone()
        val current = index.affiliations[person.id].orEmpty().filter { it.current }
        val notes = index.notes[person.id].orEmpty()
        return JSONObject()
            .put("id", person.id)
            .put("name", person.name)
            .apply {
                if (person.isSelf) put("is_user", true)
                if (person.archived) put("archived", true)
                current.filterNot { it.isEducation }.joinToString("; ") { it.label }.takeIf(String::isNotEmpty)?.let { put("work", it) }
                current.filter { it.isEducation }.joinToString("; ") { it.label }.takeIf(String::isNotEmpty)?.let { put("study", it) }
                person.location.takeIf(String::isNotBlank)?.let { put("location", it) }
                person.relationship.takeIf(String::isNotBlank)?.let { put("relationship", it) }
                person.tags.takeIf(String::isNotBlank)?.let { put("tags", it) }
                put("notes", notes.size)
                put("active_needs", index.needs[person.id].orEmpty().count { it.status == NeedEntity.STATUS_ACTIVE })
                put("active_capabilities", index.capabilities[person.id].orEmpty().count { it.active })
                put("background_facts", index.facts[person.id].orEmpty().size)
                notes.maxOfOrNull { it.occurredAt }?.let { put("latest_note", formatToolDate(it, zone)) }
            }
    }

    private fun affiliationJson(item: AffiliationEntity, zone: ZoneId): JSONObject = JSONObject()
        .put("ref", RecordRef.of(item).toString())
        .put("organization", item.organization)
        .put("role", item.role)
        .put("current", item.current)
        .put("confirmed", formatToolDate(item.lastConfirmedAt, zone))
        .putOpt("source_note_id", item.sourceInteractionId)

    private fun recordJson(ref: RecordRef, text: String, active: Boolean, confirmed: Long, sourceNoteId: Long?, zone: ZoneId) =
        JSONObject()
            .put("ref", ref.toString())
            .put("text", text)
            .put("active", active)
            .put("confirmed", formatToolDate(confirmed, zone))
            .putOpt("source_note_id", sourceNoteId)

    /** Fills a page up to a size budget, so one enormous record cannot blow the tool output limit. */
    private fun <T> page(items: List<T>, offset: Int, render: (T) -> JSONObject): Pair<JSONArray, Int?> {
        val out = JSONArray()
        var used = 0
        var index = offset.coerceIn(0, items.size)
        while (index < items.size) {
            val json = render(items[index])
            val size = json.toString().length
            if (out.length() > 0 && used + size > PAGE_CHARACTERS) break
            out.put(json)
            used += size
            index += 1
        }
        return out to index.takeIf { it < items.size }
    }

    private fun offset(input: JSONObject): Int = (input.optionalLong("offset") ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private suspend fun nameOf(personId: Long): String = store.person(personId)?.name ?: "person $personId"

    private fun textOrKeep(input: JSONObject, current: String): String {
        val text = input.optionalText("text", 1_000) ?: return current
        if (text.isEmpty()) throw ToolFailure("Record text cannot be empty.")
        return text
    }

    private fun parseRef(value: String): RecordRef = RecordRef.parse(value)
        ?: throw ToolFailure("\"$value\" is not a record reference; they look like need:12.")

    private fun waiting(action: PendingAction): JSONObject = JSONObject()
        .put("status", "waiting_for_user")
        .put("card", action.description)
        .put("note", "Nothing has happened yet. The user confirms or declines this on a card under your reply.")

    private fun nextActionId(log: AgentTurnLog): String = "action_${log.pending.size + 1}"

    private fun describeCall(name: String, input: JSONObject): String {
        val safe = JSONObject(input.toString())
        if (safe.has("contact")) safe.put("contact", CONTACT_PLACEHOLDER)
        return "$name ${safe.toString().take(400)}"
    }

    private fun sameText(first: String, second: String): Boolean = normalizeText(first) == normalizeText(second)

    private fun duplicate(person: PersonEntity, ref: RecordRef, text: String) =
        ToolFailure("${person.name} already has $ref: $text. Update that record instead of adding another.")

    private fun nothingToChange(ref: RecordRef) = ToolFailure("Nothing would change: $ref already says that.")

    private fun noNote(id: Long) = ToolFailure("No saved note has id $id.")

    private fun noRecord(ref: RecordRef) = ToolFailure("No saved record is $ref.")

    private companion object {
        const val MAX_MATCHES = 15
        const val DEFAULT_NOTES = 20L
        const val MAX_NOTE_EXCERPT = 1_500
        const val MAX_RECORDS_PER_NOTE = 20

        /** Well under what Claude Code accepts from one tool call. */
        const val PAGE_CHARACTERS = 40_000

        fun noPerson(id: Long) = ToolFailure("No saved person has id $id.")
    }
}

private fun RecordKind.withArticle(): String = when (this) {
    RecordKind.EDUCATION -> "education"
    RecordKind.FACT -> "a background fact"
    else -> "a $label"
}

/** Case and punctuation aside, the same words. */
private fun normalizeText(value: String): String = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun List<String>.joinToEnglish(): String = when (size) {
    0 -> ""
    1 -> single()
    else -> dropLast(1).joinToString(", ") + " and " + last()
}
