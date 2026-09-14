package com.azizjon.network.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.azizjon.network.data.AssistantStore
import com.azizjon.network.data.InteractionEntity
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkDatabase
import com.azizjon.network.data.RecordKind
import com.azizjon.network.data.RecordRef
import com.azizjon.network.data.UndoConflictException
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The assistant's tools against a real Room database, synthetic records only.
 *
 * These are the guarantees the agent design leans on: writes land together and
 * undo together, undo never overwrites newer data, and deletes and merges only
 * ever queue.
 */
@RunWith(AndroidJUnit4::class)
class AssistantToolsInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-09-14T12:00:00Z")
    private lateinit var database: NetworkDatabase
    private lateinit var store: AssistantStore
    private lateinit var tools: AssistantTools
    private var writesMarked = 0

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, NetworkDatabase::class.java).build()
        store = AssistantStore(database)
        tools = AssistantTools(store, clock = { now }, zone = { ZoneOffset.UTC }, beforeWrite = { writesMarked += 1 })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aCaptureSavesANoteWithLinkedRecordsAndOneUndoRemovesAllOfIt() = runBlocking {
        val log = AgentTurnLog()
        val personId = ok(log, "create_person", JSONObject().put("name", "Marta Okafor").put("location", "Lisbon")).getLong("person_id")
        val noteId = ok(log, "add_note", JSONObject().put("person_id", personId).put("text", "Met Marta at the design fair.").put("date", "2026-09-12"))
            .getLong("note_id")
        val position = ok(
            log,
            "add_record",
            JSONObject().put("person_id", personId).put("kind", "position").put("organization", "Brightline Studio")
                .put("role", "Product designer").put("source_note_id", noteId),
        ).getString("record")
        ok(log, "add_record", JSONObject().put("person_id", personId).put("kind", "fact").put("text", "Volunteers at Harbor Animal Shelter on weekends.").put("source_note_id", noteId))

        assertTrue(position.startsWith("position:"))
        val snapshot = store.snapshot()
        assertEquals(InteractionEntity.ORIGIN_ASSISTANT, snapshot.interactions.single().origin)
        assertEquals(Instant.parse("2026-09-12T00:00:00Z").toEpochMilli(), snapshot.interactions.single().occurredAt)
        assertEquals(noteId, snapshot.affiliations.single().sourceInteractionId)
        assertEquals(listOf("Added Marta Okafor", "Saved a note on Marta Okafor"), log.saved.take(2))
        assertEquals("every write marks a backup as due", 4, writesMarked)

        store.undo(log.changes, now.toEpochMilli())

        val after = store.snapshot()
        assertTrue(after.people.isEmpty())
        assertTrue(after.interactions.isEmpty())
        assertTrue(after.affiliations.isEmpty())
        assertTrue(after.facts.isEmpty())
    }

    @Test
    fun aNoteCarriesItsRecordsInOneCallAndSkipsOnlyTheOnesThatCannotBeSaved() = runBlocking {
        val setup = AgentTurnLog()
        val personId = ok(setup, "create_person", JSONObject().put("name", "Dana Whitfield")).getLong("person_id")
        ok(setup, "add_record", JSONObject().put("person_id", personId).put("kind", "capability").put("text", "Knows seed investors in Berlin."))

        val log = AgentTurnLog()
        val result = ok(
            log,
            "add_note",
            JSONObject()
                .put("person_id", personId)
                .put("text", "Dana joined Lumen Labs as an advisor and still knows seed investors in Berlin.")
                .put("date", "2026-09-07")
                .put(
                    "records",
                    JSONArray()
                        .put(JSONObject().put("kind", "position").put("organization", "Lumen Labs").put("role", "Advisor"))
                        .put(JSONObject().put("kind", "capability").put("text", "knows seed investors in Berlin"))
                        .put(JSONObject().put("kind", "position")),
                ),
        )

        val snapshot = store.snapshot()
        val note = snapshot.interactions.single()
        assertEquals(1, result.getJSONArray("records").length())
        assertEquals("the duplicate and the empty position are reported, not saved", 2, result.getJSONArray("skipped").length())
        val position = snapshot.affiliations.single()
        assertEquals(note.id, position.sourceInteractionId)
        assertEquals("a record defaults to the note's date", note.occurredAt, position.lastConfirmedAt)
        assertEquals(1, snapshot.capabilities.size)
        assertTrue(log.saved.contains("Added a position for Dana Whitfield: Advisor at Lumen Labs"))

        store.undo(log.changes, now.toEpochMilli())
        val after = store.snapshot()
        assertTrue(after.interactions.isEmpty())
        assertTrue(after.affiliations.isEmpty())
        assertEquals("what existed before the reply is untouched", 1, after.capabilities.size)
    }

    @Test
    fun duplicatesStrangersAndOtherPeoplesNotesAreRefusedWithAReadableReason() = runBlocking {
        val log = AgentTurnLog()
        val ana = ok(log, "create_person", JSONObject().put("name", "Ana Lee")).getLong("person_id")
        val ben = ok(log, "create_person", JSONObject().put("name", "Ben Carter")).getLong("person_id")
        val bensNote = ok(log, "add_note", JSONObject().put("person_id", ben).put("text", "Ben is hiring.")).getLong("note_id")

        assertTrue(error(log, "create_person", JSONObject().put("name", "ana lee")).contains("already saved"))
        ok(log, "create_person", JSONObject().put("name", "Ana Lee").put("allow_same_name", true))

        ok(log, "add_record", JSONObject().put("person_id", ana).put("kind", "need").put("text", "Looking for a co-founder."))
        assertTrue(error(log, "add_record", JSONObject().put("person_id", ana).put("kind", "need").put("text", "looking for a co-founder")).contains("already has need:"))
        assertTrue(error(log, "get_person", JSONObject().put("person_id", 999)).contains("No saved person has id 999"))
        assertTrue(
            error(log, "add_record", JSONObject().put("person_id", ana).put("kind", "capability").put("text", "Hiring advice.").put("source_note_id", bensNote))
                .contains("on somebody else"),
        )
        assertTrue(error(log, "add_record", JSONObject().put("person_id", ana).put("kind", "position")).contains("organization or a role"))
        assertTrue(error(log, "add_note", JSONObject().put("person_id", ana).put("text", "x").put("date", "2026-10-01")).contains("future"))
        assertTrue(error(log, "not_a_tool", JSONObject()).contains("no tool called"))
    }

    @Test
    fun aRecordChangesKindKeepingItsDateAndSourceAndUndoRestoresTheOriginal() = runBlocking {
        val setup = AgentTurnLog()
        val personId = ok(setup, "create_person", JSONObject().put("name", "Dana Whitfield")).getLong("person_id")
        val noteId = ok(setup, "add_note", JSONObject().put("person_id", personId).put("text", "Dana can introduce seed investors.")).getLong("note_id")
        val needRef = ok(
            setup,
            "add_record",
            JSONObject().put("person_id", personId).put("kind", "need").put("text", "Introduces seed investors in Berlin.")
                .put("date", "2026-09-01").put("source_note_id", noteId),
        ).getString("record")
        val original = store.snapshot().needs.single()

        val log = AgentTurnLog()
        val capabilityRef = RecordRef.parse(ok(log, "change_record_kind", JSONObject().put("record", needRef).put("new_kind", "capability")).getString("record"))!!

        val converted = store.snapshot()
        assertTrue(converted.needs.isEmpty())
        val capability = converted.capabilities.single()
        assertEquals(RecordKind.CAPABILITY, capabilityRef.kind)
        assertEquals(original.text, capability.text)
        assertEquals(original.lastConfirmedAt, capability.lastConfirmedAt)
        assertEquals(noteId, capability.sourceInteractionId)

        store.undo(log.changes, now.toEpochMilli())

        val restored = store.snapshot()
        assertTrue(restored.capabilities.isEmpty())
        assertEquals(original, restored.needs.single())

        // Work and study share a row, so switching between them keeps the id.
        val positionRef = ok(setup, "add_record", JSONObject().put("person_id", personId).put("kind", "position").put("organization", "Cedar Hill").put("role", "Spanish"))
            .getString("record")
        val education = ok(AgentTurnLog(), "change_record_kind", JSONObject().put("record", positionRef).put("new_kind", "education")).getString("record")
        assertEquals(positionRef.substringAfter(':'), education.substringAfter(':'))
        assertTrue(store.snapshot().affiliations.single().isEducation)
        assertTrue(error(AgentTurnLog(), "change_record_kind", JSONObject().put("record", positionRef).put("new_kind", "education")).contains("already"))
    }

    @Test
    fun movingANoteTakesItsRecordsAndUndoBringsThemBack() = runBlocking {
        val setup = AgentTurnLog()
        val wrong = ok(setup, "create_person", JSONObject().put("name", "Priya Raman")).getLong("person_id")
        val right = ok(setup, "create_person", JSONObject().put("name", "Priya Rao")).getLong("person_id")
        val noteId = ok(setup, "add_note", JSONObject().put("person_id", wrong).put("text", "Priya runs a robotics club.")).getLong("note_id")
        ok(setup, "add_record", JSONObject().put("person_id", wrong).put("kind", "fact").put("text", "Runs a robotics club.").put("source_note_id", noteId))
        ok(setup, "add_record", JSONObject().put("person_id", wrong).put("kind", "need").put("text", "Wants a mentor."))

        val log = AgentTurnLog()
        assertEquals(1, ok(log, "move_note", JSONObject().put("note_id", noteId).put("to_person_id", right)).getInt("records_moved"))
        val moved = store.snapshot()
        assertEquals(right, moved.interactions.single().personId)
        assertEquals(right, moved.facts.single().personId)
        assertEquals("a record typed by hand stays put", wrong, moved.needs.single().personId)

        store.undo(log.changes, now.toEpochMilli())
        val back = store.snapshot()
        assertEquals(wrong, back.interactions.single().personId)
        assertEquals(wrong, back.facts.single().personId)
    }

    @Test
    fun undoRefusesRatherThanOverwriteAnythingEditedSince() = runBlocking {
        val log = AgentTurnLog()
        val personId = ok(log, "create_person", JSONObject().put("name", "Sam Ortiz")).getLong("person_id")
        ok(log, "add_record", JSONObject().put("person_id", personId).put("kind", "need").put("text", "Needs a lawyer."))
        val dao = database.networkDao()
        val need = dao.allNeeds().single()
        dao.updateNeed(need.copy(text = "Needs an immigration lawyer.", status = NeedEntity.STATUS_CLOSED))

        assertThrows(UndoConflictException::class.java) { runBlocking { store.undo(log.changes, now.toEpochMilli()) } }
        assertEquals("Needs an immigration lawyer.", dao.allNeeds().single().text)
        assertEquals(1, dao.allPeople().size)

        // A person the reply created who has since gained a note of their own is
        // not removed along with that note.
        val second = AgentTurnLog()
        val created = ok(second, "create_person", JSONObject().put("name", "Lee Park")).getLong("person_id")
        dao.insertInteraction(InteractionEntity(personId = created, note = "Typed by hand later.", occurredAt = 1, createdAt = 1))
        assertThrows(UndoConflictException::class.java) { runBlocking { store.undo(second.changes, now.toEpochMilli()) } }
        assertNotNull(dao.person(created))
    }

    @Test
    fun deletesAndMergesOnlyEverQueueUntilTheUserCarriesThemOut() = runBlocking {
        val setup = AgentTurnLog()
        val keep = ok(setup, "create_person", JSONObject().put("name", "Ana Lee").put("tags", "design")).getLong("person_id")
        val duplicate = ok(setup, "create_person", JSONObject().put("name", "Ana L.").put("location", "Seoul").put("tags", "Design, investors")).getLong("person_id")
        val note = ok(setup, "add_note", JSONObject().put("person_id", duplicate).put("text", "Ana is raising a seed round.")).getLong("note_id")
        ok(setup, "add_record", JSONObject().put("person_id", duplicate).put("kind", "need").put("text", "Raising a seed round.").put("source_note_id", note))

        val log = AgentTurnLog()
        assertEquals("waiting_for_user", ok(log, "delete_person", JSONObject().put("person_id", duplicate)).getString("status"))
        ok(log, "delete_person", JSONObject().put("person_id", duplicate))
        ok(log, "merge_people", JSONObject().put("keep_person_id", keep).put("merge_person_id", duplicate))
        ok(log, "delete_record", JSONObject().put("record", "need:${store.snapshot().needs.single().id}"))

        assertEquals("asking twice queues once", 3, log.pending.size)
        assertTrue(log.changes.isEmpty())
        assertEquals(2, store.snapshot().people.size)
        val deletion = log.pending.first() as PendingAction.DeletePerson
        assertEquals(2, deletion.ownedRows)

        val merged = store.mergePeople(keep, duplicate, now.toEpochMilli())
        assertEquals("Ana L.", merged.mergedName)
        val after = store.snapshot()
        val kept = after.people.single()
        assertEquals(keep, kept.id)
        assertEquals("Seoul", kept.location)
        assertEquals("design, investors", kept.tags.lowercase())
        assertEquals(keep, after.interactions.single().personId)
        assertEquals(keep, after.needs.single().personId)
    }

    @Test
    fun readsFindPeopleByNameAndWordsAndBrowseOnlyWhatIsCurrent() = runBlocking {
        val setup = AgentTurnLog()
        val ana = ok(setup, "create_person", JSONObject().put("name", "Ana Lee")).getLong("person_id")
        val ben = ok(setup, "create_person", JSONObject().put("name", "Ben Carter")).getLong("person_id")
        ok(setup, "add_record", JSONObject().put("person_id", ana).put("kind", "position").put("organization", "Northwind Labs").put("role", "CTO"))
        ok(setup, "add_record", JSONObject().put("person_id", ben).put("kind", "position").put("organization", "Old Co").put("role", "Engineer").put("current", false))
        val closed = ok(setup, "add_record", JSONObject().put("person_id", ben).put("kind", "need").put("text", "Wants a new job.")).getString("record")
        ok(setup, "update_record", JSONObject().put("record", closed).put("active", false))
        ok(setup, "add_note", JSONObject().put("person_id", ben).put("text", "Talked about robotics.").put("date", "2026-08-02"))
        ok(setup, "update_person", JSONObject().put("person_id", ben).put("archived", true))

        val found = ok(AgentTurnLog(), "find_people", JSONObject().put("query", "Northwind")).getJSONArray("matches")
        assertEquals(1, found.length())
        assertEquals(ana, found.getJSONObject(0).getLong("id"))

        val people = ok(AgentTurnLog(), "list_people", JSONObject()).getJSONArray("people")
        assertEquals("archived people are hidden by default", listOf("Ana Lee"), names(people))
        assertEquals(listOf("Ana Lee", "Ben Carter"), names(ok(AgentTurnLog(), "list_people", JSONObject().put("include_archived", true)).getJSONArray("people")))

        val current = ok(AgentTurnLog(), "browse_records", JSONObject()).getJSONArray("records")
        assertEquals(1, current.length())
        assertEquals("CTO at Northwind Labs", current.getJSONObject(0).getString("text"))
        assertEquals(3, ok(AgentTurnLog(), "browse_records", JSONObject().put("include_inactive", true)).getJSONArray("records").length())

        val august = ok(AgentTurnLog(), "search_notes", JSONObject().put("from", "2026-08-01").put("to", "2026-08-31")).getJSONArray("notes")
        assertEquals(1, august.length())
        assertEquals(0, ok(AgentTurnLog(), "search_notes", JSONObject().put("query", "robotics").put("to", "2026-07-31")).getJSONArray("notes").length())

        val detail = ok(AgentTurnLog(), "get_person", JSONObject().put("person_id", ben))
        assertTrue(detail.getBoolean("archived"))
        assertFalse(detail.getJSONArray("needs").getJSONObject(0).getBoolean("active"))
    }

    @Test
    fun aContactValueIsSavedButNeverCopiedIntoTheLogOrReadBack() = runBlocking {
        val log = AgentTurnLog()
        val personId = ok(log, "create_person", JSONObject().put("name", "Ana Lee")).getLong("person_id")
        ok(log, "update_person", JSONObject().put("person_id", personId).put("contact", "ana@example.test").put("location", "Seoul"))

        assertEquals("ana@example.test", store.person(personId)?.contact)
        assertTrue(log.calls.none { it.contains("ana@example.test") })
        assertTrue(log.saved.contains("Updated Ana Lee's location and contact"))
        val read = ok(AgentTurnLog(), "get_person", JSONObject().put("person_id", personId))
        assertFalse(read.toString().contains("ana@example.test"))
        assertTrue(read.getBoolean("has_contact"))
    }

    private suspend fun ok(log: AgentTurnLog, name: String, input: JSONObject): JSONObject {
        val result = tools.execute(name, input, log)
        assertFalse("$name failed: ${result.output}", result.isError)
        return JSONObject(result.output)
    }

    private suspend fun error(log: AgentTurnLog, name: String, input: JSONObject): String {
        val result = tools.execute(name, input, log)
        assertTrue("$name should have been refused but returned ${result.output}", result.isError)
        return result.output
    }

    private fun names(people: JSONArray): List<String> = (0 until people.length()).map { people.getJSONObject(it).getString("name") }
}
