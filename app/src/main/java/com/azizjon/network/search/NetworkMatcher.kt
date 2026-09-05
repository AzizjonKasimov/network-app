package com.azizjon.network.search

import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity

data class SearchEvidence(
    val kind: String,
    val text: String,
    val recordedAt: Long,
    val score: Int,
)

data class PersonSearchResult(
    val person: PersonEntity,
    val score: Int,
    val evidence: List<SearchEvidence>,
)

object NetworkMatcher {
    private val stopWords = setOf(
        "a", "an", "and", "can", "could", "do", "for", "help", "i", "in", "is", "me",
        "my", "of", "on", "someone", "that", "the", "to", "who", "with",
    )

    fun search(snapshot: NetworkSnapshot, query: String): List<PersonSearchResult> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        val tokens = normalized.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 && it !in stopWords }
            .distinct()
            .ifEmpty { listOf(normalized) }

        return snapshot.people.asSequence()
            .filterNot { it.archived }
            .mapNotNull { person -> matchPerson(snapshot, person, normalized, tokens) }
            .sortedWith(compareByDescending<PersonSearchResult> { it.score }.thenBy { it.person.name.lowercase() })
            .toList()
    }

    private fun matchPerson(
        snapshot: NetworkSnapshot,
        person: PersonEntity,
        query: String,
        tokens: List<String>,
    ): PersonSearchResult? {
        val evidence = buildList {
            val profile = listOf(person.name, person.organization, person.role, person.location, person.tags, person.relationship, person.notes)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            score(profile, query, tokens, 2)?.let { add(SearchEvidence("Profile", profile, person.updatedAt, it)) }
            snapshot.capabilitiesFor(person.id).forEach { item ->
                if (item.active) {
                    score(item.text, query, tokens, 6)?.let { add(SearchEvidence("Capability", item.text, item.lastConfirmedAt, it)) }
                }
            }
            snapshot.needsFor(person.id).forEach { item ->
                if (item.status == "active") {
                    score(item.text, query, tokens, 4)?.let { add(SearchEvidence("Need / goal", item.text, item.lastConfirmedAt, it)) }
                }
            }
            snapshot.interactionsFor(person.id).forEach { item ->
                score(item.note, query, tokens, 3)?.let { add(SearchEvidence("Interaction", item.note, item.occurredAt, it)) }
            }
        }.sortedByDescending { it.score }

        if (evidence.isEmpty()) return null
        return PersonSearchResult(person, evidence.sumOf { it.score }, evidence.take(4))
    }

    private fun score(text: String, query: String, tokens: List<String>, weight: Int): Int? {
        val haystack = text.lowercase()
        val tokenHits = tokens.count { it in haystack }
        if (tokenHits == 0 && query !in haystack) return null
        return (tokenHits * weight) + if (query in haystack) weight * 2 else 0
    }
}
