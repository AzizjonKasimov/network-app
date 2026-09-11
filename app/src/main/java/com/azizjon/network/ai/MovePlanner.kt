package com.azizjon.network.ai

import com.azizjon.network.data.InteractionEntity
import com.azizjon.network.data.MoveDestination
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity

/**
 * One note the user could be asking to move, with what would travel with it.
 *
 * The counts are computed here rather than at confirmation time so the card can
 * say what a move costs before it happens, which is the whole point of asking.
 */
data class MoveCandidate(
    val interaction: InteractionEntity,
    val linkedRecords: Int,
)

/** A move the user has been asked to confirm. */
data class MovePlan(
    val from: PersonEntity,
    val candidates: List<MoveCandidate>,
    val selectedInteractionId: Long,
    val destination: MoveDestination,
    val destinationName: String,
    /** True when the destination does not exist yet and confirming creates it. */
    val createsPerson: Boolean,
) {
    val selected: MoveCandidate
        get() = candidates.first { it.interaction.id == selectedInteractionId }
}

/**
 * What the app decided to do with a "move that note" request.
 *
 * [Problem] is a plain sentence for the thread rather than an exception: a name
 * that resolves to nobody is an ordinary thing for the user to say, not a
 * failure of the request.
 */
sealed interface MoveOutcome {
    data class Ready(val plan: MovePlan) : MoveOutcome

    data class Problem(val message: String) : MoveOutcome
}

/**
 * Works out which note a chat move request means, entirely on the phone.
 *
 * The gateway names the two people and nothing else: it never sees the note
 * being moved, the notes competing to be it, or what they created. Resolving
 * the rest locally keeps a move at the same disclosure as the routing call that
 * carried it, which is the message the user already chose to send.
 *
 * Which note is a guess, so it is offered rather than assumed - the most recent
 * first, because a misfiled capture is almost always noticed straight after it
 * lands, with the rest listed for when it was not.
 */
object MovePlanner {
    /** Notes offered in the card. Enough to cover a late correction, not a browse. */
    const val MAX_CANDIDATES = 5

    fun plan(snapshot: NetworkSnapshot, fromName: String, toName: String): MoveOutcome {
        val source = when {
            fromName.isBlank() -> return MoveOutcome.Problem("Tell me whose note to move, and who it belongs to.")
            else -> PersonResolver.resolve(snapshot.people, fromName).let { candidates ->
                candidates.exact
                    ?: return MoveOutcome.Problem(
                        when {
                            candidates.suggestions.isEmpty() -> "I could not find $fromName in your network."
                            else -> "I found more than one $fromName: " +
                                candidates.suggestions.joinToString(", ") { it.name } +
                                ". Open the right one and use Move under Interactions."
                        },
                    )
            }
        }

        val notes = snapshot.interactionsFor(source.id)
            .sortedWith(compareByDescending<InteractionEntity> { it.occurredAt }.thenByDescending { it.id })
        if (notes.isEmpty()) return MoveOutcome.Problem("${source.name} has no notes to move.")

        val destination = resolveDestination(snapshot, source, toName)
            ?: return MoveOutcome.Problem(
                when {
                    toName.isBlank() -> "Tell me who to move ${source.name}'s note to."
                    else -> "${source.name}'s note is already on them."
                },
            )

        val candidates = notes.take(MAX_CANDIDATES).map { interaction ->
            MoveCandidate(interaction, linkedRecordCount(snapshot, interaction.id))
        }
        return MoveOutcome.Ready(
            MovePlan(
                from = source,
                candidates = candidates,
                selectedInteractionId = candidates.first().interaction.id,
                destination = destination.first,
                destinationName = destination.second,
                createsPerson = destination.first is MoveDestination.NewPerson,
            ),
        )
    }

    /**
     * Everything the named note created, which travels with it.
     *
     * Profile fields are deliberately not counted: a patch overwrote a column in
     * place and leaves nothing to trace back, so it cannot move. The card says
     * so rather than quietly under-reporting.
     */
    fun linkedRecordCount(snapshot: NetworkSnapshot, interactionId: Long): Int =
        snapshot.needs.count { it.sourceInteractionId == interactionId } +
            snapshot.capabilities.count { it.sourceInteractionId == interactionId } +
            snapshot.affiliations.count { it.sourceInteractionId == interactionId } +
            snapshot.facts.count { it.sourceInteractionId == interactionId }

    /**
     * Picks the person a note is going to, or offers to create them.
     *
     * An unknown name becoming a new person is the same choice the manual dialog
     * offers, and the common case it exists for: a note filed under whoever was
     * mentioned beside a stranger. Confirmation still gates the write.
     */
    private fun resolveDestination(
        snapshot: NetworkSnapshot,
        source: PersonEntity,
        toName: String,
    ): Pair<MoveDestination, String>? {
        val target = toName.trim()
        if (target.isBlank()) return null
        val candidates = PersonResolver.resolve(snapshot.people, target)
        val existing = candidates.exact ?: candidates.suggestions.singleOrNull()
        if (existing != null) {
            return if (existing.id == source.id) null else MoveDestination.Existing(existing.id) to existing.name
        }
        return MoveDestination.NewPerson(target) to target
    }

}
