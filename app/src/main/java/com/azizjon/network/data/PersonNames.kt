package com.azizjon.network.data

import com.azizjon.network.checkin.NameKey

/**
 * A person's other names, stored one per line in [PersonEntity.aliases].
 *
 * They are the names the person turns up under elsewhere: how the phone's
 * contacts or a chat app shows them ("Minsu (Lumen CTO)"), their name in
 * another script, a nickname. The check-in and the assistant's lookups
 * recognize a person by any of them, so a contact saved differently from the
 * network is not taken for somebody new.
 */
object PersonNames {
    const val MAX_NAMES = 20
    const val MAX_LENGTH = 200

    fun split(aliases: String): List<String> = aliases.lines().map(String::trim).filter(String::isNotEmpty)

    /**
     * [names] ready to store: one line each, none twice, none that is only
     * [mainName] again, and at most [MAX_NAMES], keeping the first.
     */
    fun clean(mainName: String, names: List<String>): String {
        val main = NameKey.exact(mainName)
        return names.asSequence()
            .map { it.replace(WHITESPACE, " ").trim().take(MAX_LENGTH) }
            .filter { NameKey.exact(it).let { key -> key.isNotEmpty() && key != main } }
            .distinctBy(NameKey::exact)
            .take(MAX_NAMES)
            .joinToString("\n")
    }

    /**
     * [after], saved over [before], with [otherNames] as its other names.
     *
     * A main name that changes is kept, first among the other names, because
     * the phone's contacts and chat apps still show it. Former names are the
     * other names that used to be the main name: [before]'s, the name just
     * replaced, and [alsoFormer]. The check-in matches them as it matches a
     * main name, and one taken off the other names stops being a former name.
     */
    fun withNames(
        after: PersonEntity,
        before: PersonEntity?,
        otherNames: List<String>,
        alsoFormer: List<String> = emptyList(),
    ): PersonEntity {
        val replaced = listOfNotNull(before?.name?.takeIf { it != after.name })
        val aliases = clean(after.name, replaced + otherNames)
        val former = (before?.formerNames.orEmpty() + replaced + alsoFormer).map(NameKey::exact).toSet()
        return after.copy(aliases = aliases, formerAliases = split(aliases).filter { NameKey.exact(it) in former }.joinToString("\n"))
    }

    private val WHITESPACE = Regex("""\s+""")
}
