package com.azizjon.network.feedback

import com.azizjon.network.data.PersonEntity

/**
 * Swaps the people in a report for stable placeholders before it leaves the phone.
 *
 * A report is written to be handed to someone - or something - outside the app,
 * and the faults worth fixing are almost never about who a person is. Replacing
 * each saved person with a fixed placeholder keeps the part that matters: a
 * report can still say the note about Person 2 was attached to Person 5, which
 * is the whole content of a wrong-target bug.
 *
 * Two limits are real and are stated in the export dialog rather than hidden
 * here. Someone who is not saved yet cannot be recognised, so a name typed in
 * free text about a stranger survives. And a first name shared by two saved
 * people cannot be attributed, so it collapses to [AMBIGUOUS_PERSON] instead of
 * naming the wrong one.
 */
class FeedbackRedactor(people: List<PersonEntity>) {
    private val rules: List<Rule> = buildRules(people)

    fun redact(text: String): String {
        if (text.isBlank()) return text
        var result = text
        rules.forEach { rule -> result = rule.apply(result) }
        return result
    }

    private class Rule(
        private val pattern: Regex,
        private val replacement: String,
        /** Lets a shape-based sweep back out of a match it should not take. */
        private val accepts: (String) -> Boolean = { true },
    ) {
        fun apply(text: String): String = pattern.replace(text) { match ->
            if (accepts(match.value)) replacement else match.value
        }
    }

    private fun buildRules(people: List<PersonEntity>): List<Rule> {
        val ordered = people.sortedBy { it.id }
        val labels = HashMap<Long, String>(ordered.size)
        var counter = 0
        ordered.forEach { person ->
            labels[person.id] = if (person.isSelf) SELF else "Person ${++counter}"
        }

        // Whole names first, so "Maria Gomez" never degrades to a bare token
        // match that would lose the surname.
        val phrases = LinkedHashMap<String, String>()
        ordered.forEach { person ->
            val name = person.name.trim()
            if (name.length >= MIN_REDACTED_LENGTH) phrases.putIfAbsent(name.lowercase(), labels.getValue(person.id))
        }

        // A token owned by two people identifies neither, so it degrades rather
        // than asserting the wrong person.
        val tokenOwners = HashMap<String, MutableSet<String>>()
        ordered.forEach { person ->
            person.name.trim().split(TOKEN_SEPARATOR)
                .filter { it.length >= MIN_REDACTED_LENGTH }
                .forEach { token ->
                    tokenOwners.getOrPut(token.lowercase()) { linkedSetOf() }.add(labels.getValue(person.id))
                }
        }
        tokenOwners.forEach { (token, owners) ->
            phrases.putIfAbsent(token, if (owners.size == 1) owners.first() else AMBIGUOUS_PERSON)
        }

        ordered.forEach { person ->
            person.contact.split(CONTACT_SEPARATOR)
                .map { it.trim() }
                .filter { it.length >= MIN_REDACTED_LENGTH }
                .forEach { value -> phrases.putIfAbsent(value.lowercase(), CONTACT) }
        }

        val nameRules = phrases.entries
            .sortedByDescending { it.key.length }
            .map { (phrase, replacement) ->
                Rule(Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(phrase) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE), replacement)
            }

        // Contact details typed into the thread never reached a person record,
        // so they are swept by shape after the known values are gone.
        return nameRules + listOf(
            Rule(EMAIL, CONTACT),
            Rule(URL, LINK),
            Rule(PHONE, CONTACT) { candidate -> candidate.count(Char::isDigit) >= MIN_PHONE_DIGITS },
        )
    }

    companion object {
        const val SELF = "Me"
        const val AMBIGUOUS_PERSON = "<a saved person>"
        const val CONTACT = "<contact>"
        const val LINK = "<link>"

        /** Two-letter tokens match far too much ordinary text to be worth it. */
        private const val MIN_REDACTED_LENGTH = 3

        private val TOKEN_SEPARATOR = Regex("[\\s.,]+")
        private val CONTACT_SEPARATOR = Regex("[,;\\n]+")
        private val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.-]+")
        private val URL = Regex("\\bhttps?://\\S+", RegexOption.IGNORE_CASE)

        /**
         * Digit runs long enough to be a phone number.
         *
         * The digit floor sits above an ISO date on purpose. `2026-09-09` has
         * eight digits and a wrong-date report is useless once the date has
         * been swept away, so anything shorter than a full dialable number is
         * left alone.
         */
        private const val MIN_PHONE_DIGITS = 9
        private val PHONE = Regex("\\+?\\d[\\d\\s().-]{5,}\\d")
    }
}
