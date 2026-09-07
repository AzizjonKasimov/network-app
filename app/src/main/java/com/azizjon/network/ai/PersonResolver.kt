package com.azizjon.network.ai

import com.azizjon.network.data.PersonEntity

data class PersonResolutionCandidates(
    val exact: PersonEntity? = null,
    val suggestions: List<PersonEntity> = emptyList(),
)

object PersonResolver {
    fun resolve(people: List<PersonEntity>, targetName: String): PersonResolutionCandidates {
        val active = people.filterNot { it.archived }
        val target = normalize(targetName)
        if (target.isBlank()) return PersonResolutionCandidates()
        val exact = active.filter { normalize(it.name) == target }
        if (exact.size == 1) return PersonResolutionCandidates(exact = exact.single())
        if (exact.size > 1) return PersonResolutionCandidates(suggestions = exact.sortedBy { it.name.lowercase() })

        val scored = active.mapNotNull { person ->
            val name = normalize(person.name)
            val score = when {
                name.isBlank() -> 0
                target.contains(name) || name.contains(target) -> 80
                else -> {
                    val targetTokens = target.split(' ').filter(String::isNotBlank).toSet()
                    val nameTokens = name.split(' ').filter(String::isNotBlank).toSet()
                    if (targetTokens.isEmpty() || nameTokens.isEmpty()) 0
                    else (60.0 * (targetTokens intersect nameTokens).size / (targetTokens union nameTokens).size).toInt()
                }
            }
            if (score >= 40) person to score else null
        }.sortedWith(compareByDescending<Pair<PersonEntity, Int>> { it.second }.thenBy { it.first.name.lowercase() })
        val top = scored.firstOrNull()?.second ?: return PersonResolutionCandidates()
        return PersonResolutionCandidates(suggestions = scored.filter { it.second >= top - 10 }.take(5).map { it.first })
    }

    /**
     * Finds the one active person a note is plainly about, without calling the gateway.
     *
     * Naming the target is the assistant's first round trip, and for an update
     * about somebody already saved the phone can answer it offline. Confidence is
     * deliberately narrow: the note must point at exactly one person, by full name
     * or by a name token nobody else shares. Every other case - no hit, two hits,
     * a token two people share, a token that is also an ordinary word - returns
     * null and takes the slower assistant path rather than guessing a target.
     */
    fun resolveFromNote(people: List<PersonEntity>, note: String): PersonEntity? {
        val words = tokenize(note)
        if (words.isEmpty()) return null
        val active = people.filterNot { it.archived }.filter { tokenize(it.name).isNotEmpty() }

        val fullNameHits = active.filter { containsRun(words, tokenize(it.name)) }
        if (fullNameHits.isNotEmpty()) return fullNameHits.singleOrNull()

        val spoken = words.toSet()
        val ownersByToken = mutableMapOf<String, MutableSet<Long>>()
        active.forEach { person ->
            tokenize(person.name)
                .filter { it.length >= MIN_DISTINCTIVE_TOKEN && it !in NAMES_THAT_ARE_ALSO_WORDS }
                .forEach { token -> ownersByToken.getOrPut(token) { mutableSetOf() }.add(person.id) }
        }
        val hinted = ownersByToken
            .filterKeys(spoken::contains)
            .values
            .filter { it.size == 1 }
            .flatten()
            .distinct()
        val id = hinted.singleOrNull() ?: return null
        return active.first { it.id == id }
    }

    private fun containsRun(words: List<String>, run: List<String>): Boolean {
        if (run.isEmpty() || run.size > words.size) return false
        return (0..words.size - run.size).any { start ->
            run.indices.all { offset -> words[start + offset] == run[offset] }
        }
    }

    private fun tokenize(value: String): List<String> =
        normalize(value).split(' ').filter(String::isNotBlank)

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private const val MIN_DISTINCTIVE_TOKEN = 3

    /** Given names that are also ordinary words, so a bare appearance proves nothing. */
    private val NAMES_THAT_ARE_ALSO_WORDS = setOf(
        "art", "bill", "chip", "dawn", "drew", "faith", "grace", "hope", "joy",
        "mark", "may", "miles", "rose", "sunny", "van", "will", "young",
    )
}
