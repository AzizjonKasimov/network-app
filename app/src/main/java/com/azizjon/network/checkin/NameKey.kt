package com.azizjon.network.checkin

import java.text.Normalizer
import java.util.Locale

/**
 * The form of a name used to tell whether two names are the same person:
 * case, accents, punctuation, emoji, a bracketed aside, and word order are
 * ignored, so "Kim Minsu", "minsu kim" and "Minsu Kim 🚀 (Lumen)" all match.
 */
object NameKey {
    fun of(name: String): String = words(name).sorted().joinToString(" ")

    /** Like [of], except that a bracketed aside counts: "Minsu (Lumen CTO)" is not "Minsu (Gym)". */
    fun exact(name: String): String = words(name, keepBrackets = true).sorted().joinToString(" ")

    /** The name's words in written order, lower case and without accents. */
    fun words(name: String, keepBrackets: Boolean = false): List<String> {
        // Decomposed first, so full-width brackets from an Asian keyboard become plain ones.
        val decomposed = Normalizer.normalize(name, Normalizer.Form.NFKD)
        val text = if (keepBrackets) decomposed else decomposed.replace(BRACKETED, " ")
        // Recomposed, or Hangul would stay split into its letters.
        val plain = Normalizer.normalize(text.replace(COMBINING_MARKS, ""), Normalizer.Form.NFC).lowercase(Locale.ROOT)
        return plain.split(NOT_A_LETTER).filter(String::isNotEmpty)
    }

    private val BRACKETED = Regex("""\([^)]*\)|\[[^]]*]""")
    private val COMBINING_MARKS = Regex("""\p{Mn}+""")
    private val NOT_A_LETTER = Regex("""[^\p{L}\p{N}]+""")
}
