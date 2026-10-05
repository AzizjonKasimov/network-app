package com.azizjon.network.checkin

import com.azizjon.network.checkin.CheckinEntity.Companion.SOURCE_CONTACTS
import com.azizjon.network.checkin.CheckinEntity.Companion.STATUS_DONE
import com.azizjon.network.checkin.CheckinEntity.Companion.STATUS_LATER
import com.azizjon.network.checkin.CheckinEntity.Companion.STATUS_NEVER
import com.azizjon.network.checkin.CheckinEntity.Companion.STATUS_OPEN
import com.azizjon.network.data.AffiliationEntity
import com.azizjon.network.data.InteractionEntity
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckinRulesTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_000 * day

    @Test
    fun someoneNewInTheAddressBookAndAChatIsOneQuestion() {
        val list = CheckinRules.build(
            rows = listOf(
                contact("c1", "Ana Lee", seen = now - 2 * day),
                chat(ChatSources.KAKAOTALK, "ana lee 🙂", seen = now - day),
            ),
            snapshot = NetworkSnapshot(),
            now = now,
        )

        val item = list.newPeople.single()
        assertEquals("ana lee 🙂", item.name)
        assertEquals(listOf(ChatSources.KAKAOTALK, SOURCE_CONTACTS), item.sources)
        assertEquals(2, item.refs.size)
    }

    @Test
    fun someoneAlreadyInTheNetworkIsNotNew() {
        val list = CheckinRules.build(
            rows = listOf(contact("c1", "Lee Ana")),
            snapshot = NetworkSnapshot(people = listOf(person(1, "Ana Lee"))),
            now = now,
        )

        assertTrue(list.newPeople.isEmpty())
        // Being in the address book is not a conversation.
        assertTrue(list.talkedTo.isEmpty())
    }

    @Test
    fun answersSettleANewPerson() {
        fun newPeople(status: String, at: Long) =
            CheckinRules.build(listOf(contact("c1", "Ana Lee", status = status, statusAt = at)), NetworkSnapshot(), now).newPeople

        assertTrue(newPeople(STATUS_NEVER, now - 400 * day).isEmpty())
        assertTrue(newPeople(STATUS_DONE, now - 400 * day).isEmpty())
        assertTrue(newPeople(STATUS_LATER, now - day).isEmpty())
        assertEquals(1, newPeople(STATUS_LATER, now - CheckinRules.LATER_PEOPLE).size)
    }

    @Test
    fun aRecentChatWithSomeoneInTheNetworkAsksForNews() {
        val ana = person(1, "Ana Lee")
        val rows = listOf(chat(ChatSources.TELEGRAM, "Ana Lee", seen = now - day))

        val asked = CheckinRules.build(rows, NetworkSnapshot(people = listOf(ana)), now).talkedTo.single()
        assertEquals(1L, asked.person.id)

        // A note saved after the chat answers it.
        val noted = NetworkSnapshot(people = listOf(ana), interactions = listOf(note(1, at = now - day / 2)))
        assertTrue(CheckinRules.build(rows, noted, now).talkedTo.isEmpty())

        // A note from three days ago means it is too soon to ask again, even about a newer chat.
        val recent = NetworkSnapshot(people = listOf(ana), interactions = listOf(note(1, at = now - 3 * day)))
        assertTrue(CheckinRules.build(rows, recent, now).talkedTo.isEmpty())

        // An old chat is not worth asking about.
        val stale = listOf(chat(ChatSources.TELEGRAM, "Ana Lee", seen = now - 20 * day))
        assertTrue(CheckinRules.build(stale, NetworkSnapshot(people = listOf(ana)), now).talkedTo.isEmpty())

        // "Nothing new" a week ago, and they have talked since.
        val answered = listOf(chat(ChatSources.TELEGRAM, "Ana Lee", seen = now - day, status = STATUS_DONE, statusAt = now - 8 * day))
        assertEquals(1, CheckinRules.build(answered, NetworkSnapshot(people = listOf(ana)), now).talkedTo.size)
    }

    @Test
    fun ambiguousArchivedOrSelfMatchesAreNotAskedAbout() {
        val rows = listOf(chat(ChatSources.TELEGRAM, "Ana Lee", seen = now - day))

        val twoAnas = NetworkSnapshot(people = listOf(person(1, "Ana Lee"), person(2, "Ana Lee")))
        val archived = NetworkSnapshot(people = listOf(person(1, "Ana Lee", archived = true)))
        val self = NetworkSnapshot(people = listOf(person(1, "Ana Lee", self = true)))

        listOf(twoAnas, archived, self).forEach { snapshot ->
            val list = CheckinRules.build(rows, snapshot, now)
            assertTrue(list.talkedTo.isEmpty())
            assertTrue(list.newPeople.isEmpty())
        }
    }

    @Test
    fun oldNeedsAndPositionsComeUpOldestFirstAFewAtATime() {
        val ana = person(1, "Ana Lee")
        val needs = (1..7).map { i -> need(i.toLong(), 1, confirmed = now - (60 + i) * day) }
        val fresh = need(20, 1, confirmed = now - 5 * day)
        val closed = need(21, 1, confirmed = now - 300 * day, status = NeedEntity.STATUS_CLOSED)
        val job = AffiliationEntity(id = 3, personId = 1, organization = "Lumen Labs", role = "Designer", lastConfirmedAt = now - 400 * day, createdAt = 0)

        val list = CheckinRules.build(
            rows = emptyList(),
            snapshot = NetworkSnapshot(people = listOf(ana), needs = needs + fresh + closed, affiliations = listOf(job)),
            now = now,
        )

        assertEquals(CheckinRules.MAX_RECORD_ITEMS, list.stillTrue.size)
        assertTrue(list.stillTrue.first() is StaleItem.Position)
        assertEquals(listOf(7L, 6L, 5L, 4L), list.stillTrue.drop(1).map { (it as StaleItem.Need).need.id })
    }

    @Test
    fun laterAndNeverQuietARecordQuestion() {
        val snapshot = NetworkSnapshot(people = listOf(person(1, "Ana Lee")), needs = listOf(need(1, 1, confirmed = now - 90 * day)))
        fun stillTrue(status: String, at: Long) =
            CheckinRules.build(listOf(record(CheckinEntity.needRef(1), status, at)), snapshot, now).stillTrue

        assertTrue(stillTrue(STATUS_LATER, now - day).isEmpty())
        assertEquals(1, stillTrue(STATUS_LATER, now - CheckinRules.LATER_RECORDS).size)
        assertTrue(stillTrue(STATUS_NEVER, now - 365 * day).isEmpty())
    }

    @Test
    fun someoneAddedRecentlyWithoutWorkOrStudyIsAskedAbout() {
        val recent = person(1, "Ana Lee", createdAt = now - 3 * day)
        val placed = person(2, "Ben Ortiz", createdAt = now - 3 * day)
        val longAgo = person(3, "Cai Wen", createdAt = now - 90 * day)
        val school = AffiliationEntity(id = 1, personId = 2, organization = "Hanyang University", lastConfirmedAt = now, createdAt = now, kind = AffiliationEntity.KIND_EDUCATION)

        val list = CheckinRules.build(emptyList(), NetworkSnapshot(people = listOf(recent, placed, longAgo), affiliations = listOf(school)), now)

        assertEquals(listOf(1L), list.missingInfo.map { it.person.id })
    }

    private fun contact(key: String, name: String, seen: Long = now, status: String = STATUS_OPEN, statusAt: Long = seen) =
        CheckinEntity(CheckinEntity.contactRef(key), SOURCE_CONTACTS, name, seen, seen, status, statusAt)

    private fun chat(source: String, name: String, seen: Long = now, status: String = STATUS_OPEN, statusAt: Long = seen) =
        CheckinEntity(CheckinEntity.chatRef(source, NameKey.of(name)), source, name, seen, seen, status, statusAt)

    private fun record(ref: String, status: String, at: Long) =
        CheckinEntity(ref, CheckinEntity.SOURCE_RECORDS, "", at, at, status, at)

    private fun person(id: Long, name: String, archived: Boolean = false, self: Boolean = false, createdAt: Long = 0) =
        PersonEntity(id = id, name = name, archived = archived, isSelf = self, createdAt = createdAt, updatedAt = createdAt)

    private fun note(personId: Long, at: Long) =
        InteractionEntity(id = at, personId = personId, note = "Synthetic note", occurredAt = at, createdAt = at)

    private fun need(id: Long, personId: Long, confirmed: Long, status: String = NeedEntity.STATUS_ACTIVE) =
        NeedEntity(id = id, personId = personId, text = "Synthetic need $id", status = status, lastConfirmedAt = confirmed, createdAt = 0)
}
