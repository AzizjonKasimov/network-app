package com.azizjon.network.ai

import com.azizjon.network.data.RowChange

enum class ChatRole { USER, ASSISTANT }

/** A structured result rendered under an assistant message. */
sealed interface ChatAttachment {
    /**
     * What one agent reply did.
     *
     * [changes] are the rows it saved, kept so the whole reply can be undone
     * together. [pending] are deletes and merges waiting for the user, which
     * never ran and so are never part of the undo.
     */
    data class AgentResult(
        val saved: List<String>,
        val changes: List<RowChange>,
        val memory: List<String>,
        val pending: List<PendingItem>,
        val people: List<Long>,
        val calls: List<String>,
        val undone: Boolean = false,
        /** Why undo refused, once it has. */
        val undoError: String? = null,
    ) : ChatAttachment {
        val canUndo: Boolean get() = changes.isNotEmpty() && !undone && undoError == null
    }
}

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val attachment: ChatAttachment? = null,
    val failed: Boolean = false,
    val sentAt: Long,
    /**
     * True when the gateway produced this turn.
     *
     * The thread also carries locally written lines - "Undid 3 changes." - and
     * those are the app talking, not the assistant. Only a gateway turn is worth
     * reporting as a bad response.
     */
    val fromGateway: Boolean = false,
    /**
     * The fault the user filed against this response, once they have.
     *
     * Held on the message purely so the thread can show that it was reported
     * and stop offering to report it twice. The report itself lives in the
     * database and outlives both this message and the whole thread.
     */
    val reportedLabel: String? = null,
)

/** What the thread is doing right now. Drives the composer and the progress row. */
sealed interface ChatPhase {
    data object Idle : ChatPhase

    /** An agent turn is running; [status] follows the tool it is on. */
    data class Working(val status: String) : ChatPhase

    data object Confirming : ChatPhase
    data object Undoing : ChatPhase

    val busy: Boolean get() = this != Idle

    val label: String
        get() = when (this) {
            Idle -> ""
            is Working -> status
            Confirming -> "Carrying that out…"
            Undoing -> "Undoing…"
        }
}

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val phase: ChatPhase = ChatPhase.Idle,
)

/** One prior turn replayed to the gateway so follow-ups keep their thread. */
data class ChatTurn(val role: ChatRole, val text: String)
