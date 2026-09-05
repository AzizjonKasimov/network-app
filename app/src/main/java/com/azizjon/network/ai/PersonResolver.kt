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

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
