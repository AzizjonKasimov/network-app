package com.azizjon.network.data

enum class ProfileField {
    NAME,
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

/** A position to add: an organization, the role held there, and whether it is current. */
data class AiAffiliationAdd(
    val organization: String,
    val role: String,
    val current: Boolean = true,
    val education: Boolean = false,
    val selected: Boolean = true,
)

data class AiAffiliationEdit(
    val id: Long,
    val organization: String,
    val role: String,
    val current: Boolean,
    val education: Boolean,
    val lastConfirmedAt: Long,
    val selected: Boolean = true,
)

/** A background fact that is not a need, a capability, or a position. */
data class AiFactAdd(
    val text: String,
    val selected: Boolean = true,
)

data class AiFactEdit(
    val id: Long,
    val text: String,
    val lastConfirmedAt: Long,
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
    val newAffiliations: List<AiAffiliationAdd> = emptyList(),
    val newFacts: List<AiFactAdd> = emptyList(),
    val interactionEdits: List<AiInteractionEdit> = emptyList(),
    val needEdits: List<AiNeedEdit> = emptyList(),
    val capabilityEdits: List<AiCapabilityEdit> = emptyList(),
    val affiliationEdits: List<AiAffiliationEdit> = emptyList(),
    val factEdits: List<AiFactEdit> = emptyList(),
)

data class AiWriteResult(
    val personId: Long,
    val auditInteractionId: Long,
)
