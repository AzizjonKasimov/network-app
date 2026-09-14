package com.azizjon.network.ai

import com.azizjon.network.data.RecordRef
import com.azizjon.network.data.RowChange

/** Stands in for a contact value anywhere one could otherwise be copied out. */
const val CONTACT_PLACEHOLDER = "<contact value omitted from reports>"

/**
 * A delete or merge the assistant asked for, which only the user can carry out.
 *
 * These are the changes that cannot be undone from the reply, so they never
 * run on the assistant's say-so. The card shows [description] and waits.
 */
sealed interface PendingAction {
    val id: String
    val description: String

    data class DeletePerson(
        override val id: String,
        val personId: Long,
        val name: String,
        val ownedRows: Int,
    ) : PendingAction {
        override val description: String
            get() = if (ownedRows == 0) "Delete $name" else "Delete $name and the $ownedRows notes and records saved about them"
    }

    data class DeleteNote(
        override val id: String,
        val noteId: Long,
        val personName: String,
        val date: String,
        val preview: String,
    ) : PendingAction {
        override val description: String get() = "Delete the $date note on $personName: “$preview”"
    }

    data class DeleteRecord(
        override val id: String,
        val ref: RecordRef,
        val personName: String,
        val text: String,
    ) : PendingAction {
        override val description: String get() = "Delete $personName's ${ref.kind.label}: $text"
    }

    data class MergePeople(
        override val id: String,
        val keepId: Long,
        val keepName: String,
        val mergeId: Long,
        val mergeName: String,
    ) : PendingAction {
        override val description: String
            get() = "Merge $mergeName into $keepName: every note and record moves to $keepName, then $mergeName is deleted"
    }
}

enum class ActionState { PENDING, DONE, KEPT, FAILED }

data class PendingItem(
    val action: PendingAction,
    val state: ActionState = ActionState.PENDING,
    /** Why it failed, or what it did once done. */
    val outcome: String? = null,
)

/**
 * Everything one agent turn did, collected as the tools run.
 *
 * Confined to the coroutine running the turn. The chat card is built from a
 * copy once the turn ends.
 */
class AgentTurnLog {
    /** Every row written, in order, for undo. */
    val changes = mutableListOf<RowChange>()

    /** One readable line per write, for the card. */
    val saved = mutableListOf<String>()

    /** The same writes with record addresses, replayed to the assistant on a follow-up. */
    val memory = mutableListOf<String>()

    val pending = mutableListOf<PendingAction>()

    /** People read or changed, in the order they came up. */
    val people = linkedSetOf<Long>()

    /** Tool calls with contact values removed, for reports. */
    val calls = mutableListOf<String>()
}
