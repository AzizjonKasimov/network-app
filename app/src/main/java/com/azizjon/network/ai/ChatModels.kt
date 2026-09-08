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
    UNCLEAR,
    ;

    companion object {
        fun parse(value: String): ChatIntent = when (value.trim().lowercase()) {
            "capture" -> CAPTURE
            "search" -> SEARCH
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
    ) : ChatAttachment {
        val applied: Boolean get() = savedPersonId != null
    }

    data class Search(val results: List<AiPersonSearchResult>) : ChatAttachment

    data class TargetChoice(val value: TargetChoiceState) : ChatAttachment
}

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val attachment: ChatAttachment? = null,
    val failed: Boolean = false,
    val sentAt: Long,
)

/** What the thread is doing right now. Drives the composer and the typing row. */
sealed interface ChatPhase {
    data object Idle : ChatPhase
    data object Routing : ChatPhase
    data object Capturing : ChatPhase
    data object Refining : ChatPhase
    data object Searching : ChatPhase
    data object Applying : ChatPhase

    val busy: Boolean get() = this != Idle

    val label: String
        get() = when (this) {
            Idle -> ""
            Routing -> "Reading your message…"
            Capturing -> "Preparing changes…"
            Refining -> "Revising the changes…"
            Searching -> "Searching your network…"
            Applying -> "Saving reviewed changes…"
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
