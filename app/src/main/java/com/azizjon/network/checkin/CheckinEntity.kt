package com.azizjon.network.checkin

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One thing the evening check-in may ask about, and what the user said to it.
 *
 * Three kinds share the table, told apart by [ref]:
 * - `contact:<lookup key>`: a contact saved in the phone's address book.
 * - `chat:<source>:<name key>`: someone who wrote in a chat app, seen only
 *   through the notification it showed. Never the message itself.
 * - `need:<id>`, `position:<id>` and `basics:<person id>`: a saved record, or a
 *   recently added person, the user snoozed or silenced. These reminders are
 *   worked out from the records themselves, so their rows exist only once the
 *   user answered "later" or "never".
 *
 * Whether someone is already in the network is decided when the list is built,
 * by name, so a person added through the assistant drops off by themselves.
 *
 * This is the phone's own bookkeeping, not network data: it stays out of the
 * encrypted backup, and the assistant never reads it.
 */
@Entity(tableName = "checkins", indices = [Index("source")])
data class CheckinEntity(
    @PrimaryKey val ref: String,
    /** [SOURCE_CONTACTS], a chat app from [ChatSources], or [SOURCE_RECORDS]. */
    val source: String,
    /** Who it is about, as the address book or the chat app shows them. Empty for records. */
    val name: String,
    val firstSeenAt: Long,
    /** The latest message from them, or when the contact was found. */
    val lastSeenAt: Long,
    /** [STATUS_OPEN], [STATUS_DONE], [STATUS_LATER], or [STATUS_NEVER]. */
    val status: String,
    /** When [status] was last set. "Later" counts from here. */
    val statusAt: Long,
) {
    companion object {
        const val SOURCE_CONTACTS = "contacts"
        const val SOURCE_RECORDS = "records"

        const val STATUS_OPEN = "open"

        /** Handled: added to the network, or "nothing new" after a chat. */
        const val STATUS_DONE = "done"
        const val STATUS_LATER = "later"
        const val STATUS_NEVER = "never"

        fun contactRef(lookupKey: String) = "contact:$lookupKey"
        fun chatRef(source: String, nameKey: String) = "chat:$source:$nameKey"
        fun needRef(id: Long) = "need:$id"
        fun positionRef(id: Long) = "position:$id"

        /** "Where does this new person work?", asked about someone added recently. */
        fun basicsRef(personId: Long) = "basics:$personId"
    }
}
