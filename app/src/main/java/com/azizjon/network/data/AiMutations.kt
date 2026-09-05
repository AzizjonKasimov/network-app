package com.azizjon.network.data

enum class ProfileField {
    NAME,
    ORGANIZATION,
    ROLE,
    LOCATION,
    CONTACT,
    RELATIONSHIP,
    TAGS,
    NOTES,
}

data class ProfilePatch(
    val field: ProfileField,
    val value: String,
    val selected: Boolean = true,
)

data class AiInteractionEdit(
    val id: Long,
    val note: String,
    val occurredAt: Long,
    val selected: Boolean = true,
)

data class AiNeedEdit(
    val id: Long,
    val text: String,
    val status: String,
    val lastConfirmedAt: Long,
    val selected: Boolean = true,
)

data class AiCapabilityEdit(
    val id: Long,
    val text: String,
    val active: Boolean,
    val lastConfirmedAt: Long,
    val selected: Boolean = true,
)

data class AiRecordAdd(
    val text: String,
    val selected: Boolean = true,
)

data class AiWriteProposal(
    val rawInput: String,
    val targetPersonId: Long?,
    val targetName: String,
    val occurredAt: Long,
    val interactionOnlyFacts: List<String> = emptyList(),
    val profilePatches: List<ProfilePatch> = emptyList(),
    val newNeeds: List<AiRecordAdd> = emptyList(),
    val newCapabilities: List<AiRecordAdd> = emptyList(),
    val interactionEdits: List<AiInteractionEdit> = emptyList(),
    val needEdits: List<AiNeedEdit> = emptyList(),
    val capabilityEdits: List<AiCapabilityEdit> = emptyList(),
)

data class AiWriteResult(
    val personId: Long,
    val auditInteractionId: Long,
)
