package com.azizjon.network.ai

import com.azizjon.network.checkin.NameKey
import com.azizjon.network.data.PersonEntity

data class PersonResolutionCandidates(
    val exact: PersonEntity? = null,
    val suggestions: List<PersonEntity> = emptyList(),
)

object PersonResolver {
    /**
     * People whose name, or one of their other names, matches [targetName]:
     * one [exact] match, or a short list of close ones when the name is
     * partial or shared.
     */
    fun resolve(
        people: List<PersonEntity>,
        targetName: String,
        includeArchived: Boolean = false,
    ): PersonResolutionCandidates {
        val pool = if (includeArchived) people else people.filterNot { it.archived }
        val target = normalize(targetName)
        if (target.isBlank()) return PersonResolutionCandidates()
        val exact = pool.filter { person -> names(person).any { normalize(it) == target } }
        if (exact.size == 1) return PersonResolutionCandidates(exact = exact.single())
        if (exact.size > 1) return PersonResolutionCandidates(suggestions = exact.sortedBy { it.name.lowercase() })

        val scored = pool.mapNotNull { person ->
            val score = names(person).maxOf { score(it, targetName) }
            if (score >= 40) person to score else null
        }.sortedWith(compareByDescending<Pair<PersonEntity, Int>> { it.second }.thenBy { it.first.name.lowercase() })
        val top = scored.firstOrNull()?.second ?: return PersonResolutionCandidates()
        return PersonResolutionCandidates(suggestions = scored.filter { it.second >= top - 10 }.take(5).map { it.first })
    }

    /** The one of [person]'s names closest to [targetName]: the main name unless another fits better. */
    fun matchedName(person: PersonEntity, targetName: String): String = names(person).maxBy { score(it, targetName) }

    private fun names(person: PersonEntity): List<String> = listOf(person.name) + person.otherNames

    /**
     * Compared as written and again without bracketed asides, taking the
     * better: a check-in name such as "Jordan (Quanta)" is still close to
     * Jordan Kim.
     */
    private fun score(name: String, targetName: String): Int = maxOf(
        similarity(normalize(name), normalize(targetName)),
        similarity(NameKey.words(name).joinToString(" "), NameKey.words(targetName).joinToString(" ")),
    )

    private fun similarity(name: String, target: String): Int = when {
        name.isBlank() || target.isBlank() -> 0
        name == target -> 100
        target.contains(name) || name.contains(target) -> 80
        else -> {
            val targetTokens = target.split(' ').filter(String::isNotBlank).toSet()
            val nameTokens = name.split(' ').filter(String::isNotBlank).toSet()
            (60.0 * (targetTokens intersect nameTokens).size / (targetTokens union nameTokens).size).toInt()
        }
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
