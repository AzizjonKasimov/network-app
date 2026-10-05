package com.azizjon.network.checkin

import androidx.room.withTransaction
import com.azizjon.network.checkin.CheckinEntity.Companion.SOURCE_CONTACTS
import com.azizjon.network.checkin.CheckinEntity.Companion.SOURCE_RECORDS
import com.azizjon.network.checkin.CheckinEntity.Companion.STATUS_OPEN
import com.azizjon.network.data.NetworkDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The check-in's bookkeeping: who turned up where, and what the user answered. */
class CheckinRepository(
    private val database: NetworkDatabase,
    private val contacts: ContactsScanner,
    /** Outlives any screen, so a chat notification is recorded even with the app closed. */
    private val scope: CoroutineScope,
) {
    private val dao = database.checkinDao()

    fun observe(): Flow<List<CheckinEntity>> = dao.observeAll()

    suspend fun all(): List<CheckinEntity> = dao.all()

    /** Called from the notification listener, which must not block. */
    fun recordSightings(sightings: List<Sighting>, seenAt: Long) {
        scope.launch {
            sightings.forEach { sighting ->
                val key = NameKey.of(sighting.name)
                if (key.isEmpty()) return@forEach
                val name = sighting.name.take(MAX_NAME)
                dao.recordSighting(
                    CheckinEntity(CheckinEntity.chatRef(sighting.source, key), sighting.source, name, seenAt, seenAt, STATUS_OPEN, seenAt),
                )
            }
        }
    }

    /**
     * Brings the address book's names up to date. A contact seen for the first
     * time becomes a question; one deleted from the phone stops being one,
     * while answers already given are kept.
     */
    suspend fun refreshContacts(now: Long = System.currentTimeMillis()) {
        if (!contacts.permitted) return
        val phone = withContext(Dispatchers.IO) { contacts.read() }.associateBy { CheckinEntity.contactRef(it.lookupKey) }
        database.withTransaction {
            val known = dao.bySource(SOURCE_CONTACTS).associateBy { it.ref }
            dao.insertIgnore(
                phone.filterKeys { it !in known }.map { (ref, contact) ->
                    CheckinEntity(ref, SOURCE_CONTACTS, contact.name.take(MAX_NAME), now, now, STATUS_OPEN, now)
                },
            )
            phone.forEach { (ref, contact) ->
                val name = contact.name.take(MAX_NAME)
                if (known[ref]?.let { it.name != name } == true) dao.rename(ref, name)
            }
            known.values.filter { it.ref !in phone && it.status == STATUS_OPEN }.map { it.ref }
                .chunked(MAX_BATCH).forEach { dao.delete(it) }
        }
    }

    suspend fun setStatus(refs: List<String>, status: String, now: Long = System.currentTimeMillis()) {
        refs.chunked(MAX_BATCH).forEach { dao.setStatus(it, status, now) }
    }

    /** "Later" or "never" on a record question; its row is created by the first answer. */
    suspend fun setRecordStatus(ref: String, status: String, now: Long = System.currentTimeMillis()) {
        dao.upsert(listOf(CheckinEntity(ref, SOURCE_RECORDS, "", now, now, status, now)))
    }

    private companion object {
        const val MAX_NAME = 200

        /** Below SQLite's oldest limit on bound variables. */
        const val MAX_BATCH = 500
    }
}
