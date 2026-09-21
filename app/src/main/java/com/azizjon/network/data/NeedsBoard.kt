package com.azizjon.network.data

/** The three lists on the Needs screen. Every need is in exactly one. */
enum class NeedStage {
    /** Still open, and the user has not helped yet. */
    TO_HELP,

    /** Still open, and the user has already helped. */
    HELPED,

    /** Solved or no longer relevant, whether or not the user helped. */
    CLOSED,
}

/** One need, with the person who has it. */
data class NeedItem(val need: NeedEntity, val person: PersonEntity) {
    val stage: NeedStage
        get() = when {
            need.status != NeedEntity.STATUS_ACTIVE -> NeedStage.CLOSED
            need.helpedAt != null -> NeedStage.HELPED
            else -> NeedStage.TO_HELP
        }
}

/**
 * Everyone else's needs, sorted into the Needs screen's lists.
 *
 * The user's own needs are left out, because the screen is about helping other
 * people, and so is anyone archived, the same as in search. The freshest need
 * comes first, so what somebody mentioned last week is not buried under what
 * they said a year ago; helped needs follow the most recent help instead.
 */
fun NetworkSnapshot.needsByStage(): Map<NeedStage, List<NeedItem>> {
    val others = people.filter { !it.isSelf && !it.archived }.associateBy { it.id }
    val items = needs.mapNotNull { need -> others[need.personId]?.let { NeedItem(need, it) } }
    val newestFirst = compareByDescending<NeedItem> { it.need.lastConfirmedAt }.thenByDescending { it.need.id }
    val latestHelpFirst = compareByDescending<NeedItem> { it.need.helpedAt }.thenByDescending { it.need.id }
    return NeedStage.entries.associateWith { stage ->
        items.filter { it.stage == stage }.sortedWith(if (stage == NeedStage.HELPED) latestHelpFirst else newestFirst)
    }
}
