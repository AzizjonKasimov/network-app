package com.azizjon.network.ai

import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity

data class TargetResolution(
    val targetName: String,
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

sealed interface AiCaptureState {
    data object Idle : AiCaptureState
    data object Resolving : AiCaptureState
    data object BuildingProposal : AiCaptureState
    data object Applying : AiCaptureState
    data class ChooseTarget(val value: TargetChoiceState) : AiCaptureState
    data class Preview(val proposal: com.azizjon.network.data.AiWriteProposal) : AiCaptureState
    data class Error(val message: String) : AiCaptureState
}

data class AiSearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<AiPersonSearchResult> = emptyList(),
    val message: String = "",
    val usedAi: Boolean = false,
)

data class PersonContext(
    val snapshot: NetworkSnapshot,
    val person: PersonEntity?,
)
