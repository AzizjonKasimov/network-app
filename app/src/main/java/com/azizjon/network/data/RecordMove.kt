package com.azizjon.network.data

/**
 * Where a misfiled note should end up.
 *
 * A note lands on the wrong person often enough to need a first-class fix: the
 * assistant resolves a name from a message, and a name is sometimes the wrong
 * person or somebody it has never seen. [NewPerson] is the case that matters
 * most, because the usual mistake is filing a stranger under whoever was
 * mentioned nearby.
 */
sealed interface MoveDestination {
    data class Existing(val personId: Long) : MoveDestination

    data class NewPerson(val name: String) : MoveDestination
}

/**
 * What a move actually changed.
 *
 * Counted rather than assumed so the confirmation can say what happened instead
 * of claiming success in the abstract.
 */
data class MoveResult(
    val personId: Long,
    val personName: String,
    val needs: Int,
    val capabilities: Int,
    val affiliations: Int,
    val facts: Int,
) {
    val records: Int get() = needs + capabilities + affiliations + facts

    /** "the note and 3 linked records", for the snackbar. */
    val summary: String
        get() = if (records == 0) "the note" else "the note and $records linked record" + if (records == 1) "" else "s"
}
