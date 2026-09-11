package com.azizjon.network.ai

import com.azizjon.network.data.AiWriteProposal

/**
 * What a chat turn is asking the assistant to do.
 *
 * The intent is resolved by the same round trip that already identified the
 * target person, so routing a capture costs nothing extra.
 */
enum class ChatIntent {
    CAPTURE,
    SEARCH,
    MOVE,
    UNCLEAR,
    ;

    companion object {
        fun parse(value: String): ChatIntent = when (value.trim().lowercase()) {
            "capture" -> CAPTURE
            "search" -> SEARCH
            "move" -> MOVE
            else -> UNCLEAR
        }
    }
}

enum class ChatRole { USER, ASSISTANT }

/** A structured result rendered under an assistant message. */
sealed interface ChatAttachment {
    /**
     * An editable write proposal. Unapplied while [savedPersonId] is null; once
     * written it keeps the id so the card can link to the person it changed.
     */
    data class Proposal(
        val proposal: AiWriteProposal,
        val savedPersonId: Long? = null,
        val caveat: String? = null,
        /**
         * The interaction the applied write created.
         *
         * Kept so the card can still offer to move the whole capture after it
         * has been saved, which is when a wrong target is usually noticed.
         */
        val savedInteractionId: Long? = null,
    ) : ChatAttachment {
        val applied: Boolean get() = savedPersonId != null
    }

    data class Search(val results: List<AiPersonSearchResult>) : ChatAttachment

    /**
     * A re-filing waiting to be confirmed, and then the one that happened.
     *
     * The assistant names the two people; which note is a guess, so the card
     * carries every candidate and the user's pick rather than acting on the
     * first one. Nothing is written until [movedToPersonId] is set, which is
     * also what stops the card offering the same move twice.
     */
    data class Move(
        val plan: MovePlan,
        val movedToPersonId: Long? = null,
    ) : ChatAttachment {
        val done: Boolean get() = movedToPersonId != null
    }

    data class TargetChoice(val value: TargetChoiceState) : ChatAttachment
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
     * The thread also carries locally written lines - "Discarded. Nothing was
     * saved." - and those are the app talking, not the assistant. Only a
     * gateway turn is worth reporting as a bad response.
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

/** What the thread is doing right now. Drives the composer and the typing row. */
sealed interface ChatPhase {
    data object Idle : ChatPhase
    data object Routing : ChatPhase
    data object Capturing : ChatPhase
    data object Refining : ChatPhase
    data object Searching : ChatPhase
    data object Applying : ChatPhase
    data object Moving : ChatPhase

    val busy: Boolean get() = this != Idle

    val label: String
        get() = when (this) {
            Idle -> ""
            Routing -> "Reading your message…"
            Capturing -> "Preparing changes…"
            Refining -> "Revising the changes…"
            Searching -> "Searching your network…"
            Applying -> "Saving reviewed changes…"
            Moving -> "Moving the note…"
        }
}

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val phase: ChatPhase = ChatPhase.Idle,
) {
    /**
     * The proposal still awaiting review, if any.
     *
     * Only one may be open at a time. While it is open the composer refines it
     * instead of starting a new capture, which is what makes "no, that was last
     * Tuesday" work without re-sending the whole note.
     */
    val pendingProposal: Pair<Long, AiWriteProposal>?
        get() = messages.lastOrNull { message ->
            (message.attachment as? ChatAttachment.Proposal)?.applied == false
        }?.let { message ->
            message.id to (message.attachment as ChatAttachment.Proposal).proposal
        }

    val pendingTargetChoice: Pair<Long, TargetChoiceState>?
        get() = messages.lastOrNull { it.attachment is ChatAttachment.TargetChoice }
            ?.takeIf { message -> messages.none { it.id > message.id && it.role == ChatRole.USER } }
            ?.let { it.id to (it.attachment as ChatAttachment.TargetChoice).value }
}

/** One prior turn replayed to the gateway so follow-ups keep their thread. */
data class ChatTurn(val role: ChatRole, val text: String)
