package com.azizjon.network.ai

import com.azizjon.network.data.AiWriteProposal
import com.azizjon.network.data.PersonEntity

data class TargetResolution(
    val targetName: String,
    val intent: ChatIntent,
    /**
     * For [ChatIntent.MOVE]: who holds the note now, and who it should go to.
     *
     * Names only. Which note they mean, and what it created, is worked out on
     * the phone by [MovePlanner], so a move discloses nothing beyond the message
     * the user already sent.
     */
    val moveFrom: String = "",
    val moveTo: String = "",
)

/** A proposal plus the sentence the assistant says about it in the thread. */
data class ProposalReply(
    val proposal: AiWriteProposal,
    val assistantMessage: String,
    /**
     * How the assistant handled something the model cannot represent exactly -
     * a second concurrent organization, say. Advisory only: it is shown on the
     * card and never blocks the proposal.
     */
    val caveat: String? = null,
)

/** Ranked matches plus the sentence the assistant says about them. */
data class SearchReply(
    val results: List<AiPersonSearchResult>,
    val assistantMessage: String,
)

data class TargetChoiceState(
    val rawInput: String,
    val targetName: String,
    val suggestions: List<PersonEntity>,
)

data class AiSearchEvidence(
    val id: String,
    val personId: Long,
    val kind: String,
    val text: String,
    val recordedAt: Long,
)

data class AiPersonSearchResult(
    val person: PersonEntity,
    val reasoning: String,
    val uncertainty: String,
    val evidence: List<AiSearchEvidence>,
)

data class SearchCorpus(
    val json: String,
    val peopleById: Map<Long, PersonEntity>,
    val evidenceById: Map<String, AiSearchEvidence>,
)
