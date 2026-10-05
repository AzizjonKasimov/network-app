package com.azizjon.network.checkin

import com.azizjon.network.checkin.CheckinEntity.Companion.SOURCE_CONTACTS
import com.azizjon.network.checkin.CheckinEntity.Companion.SOURCE_RECORDS
import com.azizjon.network.checkin.CheckinEntity.Companion.STATUS_DONE
import com.azizjon.network.checkin.CheckinEntity.Companion.STATUS_LATER
import com.azizjon.network.checkin.CheckinEntity.Companion.STATUS_NEVER
import com.azizjon.network.checkin.CheckinEntity.Companion.STATUS_OPEN
import com.azizjon.network.data.AffiliationEntity
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity

/** Someone from the address book or a chat app who is not in the network yet. */
data class NewPersonItem(
    val name: String,
    val refs: List<String>,
    /** Where they turned up, most recent first. */
    val sources: List<String>,
    val lastSeenAt: Long,
)

/** Someone in the network the user has chatted with since last saving anything about them. */
data class TalkedItem(
    val person: PersonEntity,
    val refs: List<String>,
    val sources: List<String>,
    val lastSeenAt: Long,
)

/** A saved record old enough that it may no longer be true. */
sealed interface StaleItem {
    val ref: String
    val person: PersonEntity
    val since: Long

    data class Need(val need: NeedEntity, override val person: PersonEntity) : StaleItem {
        override val ref: String get() = CheckinEntity.needRef(need.id)
        override val since: Long get() = need.lastConfirmedAt
    }

    data class Position(val position: AffiliationEntity, override val person: PersonEntity) : StaleItem {
        override val ref: String get() = CheckinEntity.positionRef(position.id)
        override val since: Long get() = position.lastConfirmedAt
    }
}

/** Someone added recently with nothing saved about what they do. */
data class MissingItem(val person: PersonEntity) {
    val ref: String get() = CheckinEntity.basicsRef(person.id)
}

data class CheckinList(
    val newPeople: List<NewPersonItem> = emptyList(),
    val talkedTo: List<TalkedItem> = emptyList(),
    val stillTrue: List<StaleItem> = emptyList(),
    val missingInfo: List<MissingItem> = emptyList(),
) {
    val count: Int get() = newPeople.size + talkedTo.size + stillTrue.size + missingInfo.size
}

/**
 * Decides what the check-in asks about today.
 *
 * Kept as one pure function over the stored answers and the network, so a
 * person added through the assistant, a note saved after a chat, or a need
 * closed on the Needs tab takes its question off the list by itself.
 */
object CheckinRules {
    private const val DAY = 24 * 60 * 60 * 1000L

    /** "Later" on a person brings them back after this long. */
    const val LATER_PEOPLE = 7 * DAY

    /** "Later" on a record or a missing detail. */
    const val LATER_RECORDS = 30 * DAY

    /** After a saved note or "nothing new", the wait before asking about the same person again. */
    const val TALKED_COOLDOWN = 7 * DAY

    /** A chat older than this is not worth asking about any more. */
    const val TALKED_WINDOW = 14 * DAY

    const val NEED_STALE = 60 * DAY
    const val POSITION_STALE = 180 * DAY

    /** How long after being added someone is asked about if nothing on their work or study is saved. */
    const val MISSING_WINDOW = 30 * DAY

    /**
     * Record questions shown at once, oldest first, so a long-neglected network
     * comes back a few questions a day rather than as a wall of them.
     */
    const val MAX_RECORD_ITEMS = 5

    fun build(rows: List<CheckinEntity>, snapshot: NetworkSnapshot, now: Long): CheckinList {
        val peopleByKey = snapshot.people.groupBy { NameKey.of(it.name) }
        val peopleById = snapshot.people.associateBy { it.id }
        val lastNoteAt = snapshot.interactions.groupBy { it.personId }.mapValues { (_, notes) -> notes.maxOf { it.createdAt } }
        val recordRows = rows.filter { it.source == SOURCE_RECORDS }.associateBy { it.ref }

        val newPeople = mutableListOf<NewPersonItem>()
        val talkedTo = mutableListOf<TalkedItem>()
        rows.filter { it.source != SOURCE_RECORDS }
            .groupBy { NameKey.of(it.name) }
            .filterKeys(String::isNotEmpty)
            .forEach { (key, group) ->
                val matches = peopleByKey[key].orEmpty()
                if (matches.isEmpty()) {
                    newPerson(group, now)?.let(newPeople::add)
                } else {
                    // Two saved people with the same name: asking would mean guessing which.
                    val person = matches.singleOrNull()?.takeUnless { it.archived || it.isSelf } ?: return@forEach
                    talked(person, group, lastNoteAt[person.id] ?: 0L, now)?.let(talkedTo::add)
                }
            }

        fun quiet(ref: String): Boolean {
            val row = recordRows[ref] ?: return false
            return row.status == STATUS_NEVER || (row.status == STATUS_LATER && now - row.statusAt < LATER_RECORDS)
        }

        val staleNeeds = snapshot.needs
            .filter { it.status == NeedEntity.STATUS_ACTIVE && now - it.lastConfirmedAt >= NEED_STALE }
            .mapNotNull { need -> peopleById[need.personId]?.takeUnless { it.archived }?.let { StaleItem.Need(need, it) } }
        val stalePositions = snapshot.affiliations
            .filter { it.current && !it.isEducation && now - it.lastConfirmedAt >= POSITION_STALE }
            .mapNotNull { position ->
                peopleById[position.personId]?.takeUnless { it.archived || it.isSelf }?.let { StaleItem.Position(position, it) }
            }
        val stillTrue = (staleNeeds + stalePositions).filterNot { quiet(it.ref) }.sortedBy { it.since }.take(MAX_RECORD_ITEMS)

        val placed = snapshot.affiliations.filter { it.current }.map { it.personId }.toSet()
        val missingInfo = snapshot.people
            .filter { !it.isSelf && !it.archived && it.id !in placed && now - it.createdAt <= MISSING_WINDOW }
            .map(::MissingItem)
            .filterNot { quiet(it.ref) }
            .sortedBy { it.person.createdAt }
            .take(MAX_RECORD_ITEMS)

        return CheckinList(
            newPeople = newPeople.sortedByDescending { it.lastSeenAt },
            talkedTo = talkedTo.sortedByDescending { it.lastSeenAt },
            stillTrue = stillTrue,
            missingInfo = missingInfo,
        )
    }

    /**
     * One card per name, however many places they turned up. "Never" or
     * "added" on any of them settles the name; "later" waits out its week
     * unless they turn up somewhere new meanwhile.
     */
    private fun newPerson(group: List<CheckinEntity>, now: Long): NewPersonItem? {
        if (group.any { it.status == STATUS_NEVER || it.status == STATUS_DONE }) return null
        val due = group.any { it.status == STATUS_OPEN || (it.status == STATUS_LATER && now - it.statusAt >= LATER_PEOPLE) }
        if (!due) return null
        val latest = group.maxBy { it.lastSeenAt }
        return NewPersonItem(
            name = latest.name,
            refs = group.map { it.ref },
            sources = group.sortedByDescending { it.lastSeenAt }.map { it.source }.distinct(),
            lastSeenAt = latest.lastSeenAt,
        )
    }

    /**
     * Asks "anything new?" about a chat that came after the last thing saved on
     * the person, at most once a week per person, and only while it is recent.
     * Being in the address book says nothing about talking, so contacts do not count.
     */
    private fun talked(person: PersonEntity, group: List<CheckinEntity>, lastNoteAt: Long, now: Long): TalkedItem? {
        val chats = group.filter { it.source != SOURCE_CONTACTS }
        if (chats.isEmpty() || chats.any { it.status == STATUS_NEVER }) return null
        val lastChat = chats.maxOf { it.lastSeenAt }
        val answered = chats.filter { it.status == STATUS_DONE || it.status == STATUS_LATER }.maxOfOrNull { it.statusAt } ?: 0L
        val handled = maxOf(lastNoteAt, answered)
        if (lastChat <= handled || now - handled < TALKED_COOLDOWN || now - lastChat > TALKED_WINDOW) return null
        return TalkedItem(
            person = person,
            refs = chats.map { it.ref },
            sources = chats.sortedByDescending { it.lastSeenAt }.map { it.source }.distinct(),
            lastSeenAt = lastChat,
        )
    }
}
