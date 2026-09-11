package com.azizjon.network.ai

/**
 * Decides what a chat turn means and how much of the thread the gateway may see.
 *
 * Capture and search send different amounts of data: a capture sends one
 * person's context, a search sends the whole active network behind a one-time
 * disclosure. A single thread mixes both, so replaying the history blindly
 * would push whole-network text into a request the user only ever approved as
 * single-person. [captureScopedTurns] is what keeps those scopes apart.
 */
object ChatRouter {
    /** Prior turns replayed to the gateway. Older turns are dropped first. */
    const val MAX_TURNS = 6

    /** Ceiling on replayed history so a long thread cannot crowd out the note. */
    const val MAX_HISTORY_CHARACTERS = 2_000

    private val MOVE_VERBS = Regex("""\b(move|moves|moved|moving|re-?file[ds]?|reassign(ed)?)\b""")

    private val MOVE_PHRASES = listOf(
        "wrong person", "belongs to", "belongs with", "should be on", "should be under",
    )

    private val QUESTION_OPENERS = setOf(
        "who", "which", "what", "where", "when", "why", "how", "anyone", "anybody",
        "is there", "are there", "do i know", "does anyone", "can anyone", "find", "search",
    )

    /**
     * True when a note reads like a network question rather than something to store.
     *
     * Used only to decide whether the offline person-name shortcut is safe to
     * take. "Ask Maria about funding" is a capture; "who does Maria know in
     * funding?" is a search that happens to name a saved person. When this is
     * true the gateway resolves the intent instead, so a wrong guess here costs
     * one round trip, never a wrong write.
     */
    fun looksLikeQuestion(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        if (clean.endsWith("?")) return true
        val lowered = clean.lowercase()
        val firstWord = lowered.substringBefore(' ')
        if (firstWord in QUESTION_OPENERS) return true
        return QUESTION_OPENERS.any { opener -> opener.contains(' ') && lowered.startsWith("$opener ") }
    }

    /**
     * True when a note reads like a request to re-file an existing note.
     *
     * Guards the same offline shortcut as [looksLikeQuestion], for the same
     * reason. "Move Ana's note to Ben" names two people, but when the
     * destination is somebody not saved yet only one name is recognisable, and
     * the shortcut would file the request as a fresh note about Ana instead of
     * moving hers. A false positive here - "Ana is moving to Berlin" - costs one
     * round trip and nothing else, because the gateway still decides the intent.
     */
    fun looksLikeMove(text: String): Boolean {
        val lowered = text.trim().lowercase()
        if (lowered.isEmpty()) return false
        return MOVE_VERBS.containsMatchIn(lowered) || MOVE_PHRASES.any(lowered::contains)
    }

    /**
     * History a capture-scoped request may see.
     *
     * Search turns are dropped entirely - both the question and the assistant's
     * answer, because the answer names people and quotes their evidence from
     * across the whole network. A capture request is scoped to one person and
     * must stay that way.
     */
    fun captureScopedTurns(messages: List<ChatMessage>): List<ChatTurn> =
        trim(messages.filterNot { it.carriesNetworkWideText }.map { ChatTurn(it.role, it.text) })

    /**
     * History a search request may see.
     *
     * Search already sends every active person in the same request, so replaying
     * earlier turns discloses nothing the corpus does not already contain.
     */
    fun searchScopedTurns(messages: List<ChatMessage>): List<ChatTurn> =
        trim(messages.map { ChatTurn(it.role, it.text) })

    private fun trim(turns: List<ChatTurn>): List<ChatTurn> {
        val kept = turns.filter { it.text.isNotBlank() }.takeLast(MAX_TURNS).toMutableList()
        while (kept.sumOf { it.text.length } > MAX_HISTORY_CHARACTERS && kept.isNotEmpty()) {
            kept.removeAt(0)
        }
        return kept
    }

    /** An assistant answer built from the whole network rather than one person. */
    private val ChatMessage.carriesNetworkWideText: Boolean
        get() = attachment is ChatAttachment.Search
}
