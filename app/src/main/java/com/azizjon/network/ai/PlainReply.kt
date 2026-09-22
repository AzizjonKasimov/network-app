package com.azizjon.network.ai

/**
 * Takes out the markdown Claude sometimes writes although the prompt asks for
 * plain text. The thread shows a reply as plain text, so `**Dana**` would
 * otherwise appear with its asterisks.
 *
 * It runs once, before a reply enters the thread, so what a follow-up replays
 * and what a report copies is the text the user saw. It covers the marks a
 * short reply actually uses and leaves anything else as written: the asterisk
 * in "5 * 3" or the underscores in a handle are not formatting.
 */
object PlainReply {
    private val heading = Regex("""(?m)^[ \t]{0,3}#{1,6}[ \t]+""")

    /** A `*` list marker. A `-` list already reads as plain text, so it stays. */
    private val bullet = Regex("""(?m)^([ \t]*)\*[ \t]+""")

    private val bold = Regex("""\*\*(?=\S)(.+?)(?<=\S)\*\*""")
    private val underscoreBold = Regex("""(?<![\p{L}\p{N}_])__(?=\S)(.+?)(?<=\S)__(?![\p{L}\p{N}_])""")

    /** Runs after bold, which leaves single asterisks around ***bold italic***. */
    private val italic = Regex("""(?<![*\p{L}\p{N}_])\*(?=[^\s*])(.+?)(?<=[^\s*])\*(?![*\p{L}\p{N}_])""")

    private val code = Regex("""`([^`\n]+)`""")

    fun from(reply: String): String {
        var text = heading.replace(reply, "")
        text = bullet.replace(text) { it.groupValues[1] + "- " }
        for (pair in listOf(bold, underscoreBold, italic, code)) {
            text = pair.replace(text) { it.groupValues[1] }
        }
        return text.trim()
    }
}
