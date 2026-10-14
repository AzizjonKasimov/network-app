package com.azizjon.network.checkin

import com.azizjon.network.data.PersonEntity

/**
 * Finds saved people by a name as the phone's contacts or a chat app shows it.
 *
 * A main name matches with or without a bracketed aside, so "Minsu Kim
 * (Lumen)" is Minsu Kim. An other name has to match whole, brackets included:
 * other names are mostly copied from a contact such as "Minsu (Lumen CTO)",
 * and its bracket is what tells that Minsu from "Minsu (Gym)".
 */
class PeopleByName(people: List<PersonEntity>) {
    private val byName = people.groupBy { NameKey.of(it.name) } - ""
    private val byOtherName = people
        .flatMap { person -> person.otherNames.map { NameKey.exact(it) to person } }
        .groupBy({ it.first }, { it.second }) - ""
    /** Worked out once: the list is rebuilt on every change, and a phone can hold hundreds of contacts. */
    private val suggestible = people.filterNot { it.archived || it.isSelf }
        .map { person -> person to (listOf(person.name) + person.otherNames).map { NameKey.words(it).toSet() } }

    /** Who [name] is: usually one person, nobody, or several when saved names collide. */
    fun find(name: String): List<PersonEntity> =
        (byOtherName[NameKey.exact(name)].orEmpty() + byName[NameKey.of(name)].orEmpty()).distinctBy { it.id }

    /**
     * Saved people [name] may be although it is none of their names: every
     * word of the shorter name is in the longer one, as with "Minsu (Lumen
     * CTO)" and Minsu Kim, or "Kim Minsu CTO" and Minsu Kim. An initial counts
     * for a word starting with it, so "Minsu K." fits too. A name that fits
     * more than [MAX_LIKELY] people says too little to suggest any of them.
     */
    fun likely(name: String): List<PersonEntity> {
        val words = NameKey.words(name).toSet()
        val found = suggestible.filter { (_, names) -> names.any { fits(words, it) } }.map { it.first }
        return if (found.size > MAX_LIKELY) emptyList() else found.sortedBy { it.name.lowercase() }
    }

    private fun fits(first: Set<String>, second: Set<String>): Boolean {
        if (first.isEmpty() || second.isEmpty()) return false
        val (shorter, longer) = if (first.size <= second.size) first to second else second to first
        // At least one whole word in common: an initial alone says nothing.
        return shorter.any { it.length > 1 && it in longer } &&
            shorter.all { word -> word in longer || (word.length == 1 && longer.any { it.startsWith(word) }) }
    }

    companion object {
        const val MAX_LIKELY = 3
    }
}
