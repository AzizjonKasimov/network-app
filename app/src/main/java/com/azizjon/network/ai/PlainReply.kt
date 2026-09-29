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

    /** `[text](address)` keeps its text: the thread cannot open a link, and the card lists the pages read. */
    private val link = Regex("""\[([^\[\]\n]+)]\((?:https?://|www\.)[^()\s]*(?:\([^()\s]*\)[^()\s]*)*\)""")

    /** A line that is a list item, for recognising a closing list of sources. */
    private val listItem = Regex("""^\s*(?:[-*•]|\d+[.)])\s+\S.*$""")

    /** A "Sources" line on its own, whatever emphasis it was given. */
    private val sourcesHeader = Regex("""^\s*(?:#{1,6}\s*)?[*_]*Sources?[*_]*:?[*_]*\s*$""", RegexOption.IGNORE_CASE)

    /** "Sources: [a](…), [b](…)" on one line. */
    private val inlineSources = Regex("""^\s*[*_]*Sources?[*_]*:[*_]*\s+(?:\[[^\]\n]+]\([^)\s]+\)[\s,;·|-]*)+$""", RegexOption.IGNORE_CASE)

    fun from(reply: String): String {
        var text = withoutSourceList(reply)
        text = heading.replace(text, "")
        text = bullet.replace(text) { it.groupValues[1] + "- " }
        for (pair in listOf(link, bold, underscoreBold, italic, code)) {
            text = pair.replace(text) { it.groupValues[1] }
        }
        return text.trim()
    }

    /**
     * Drops a list of sources at the very end of a reply. The web search tool
     * tells Claude to cite its pages that way, but the card under the reply
     * already lists every page read, and the note that was saved keeps them.
     */
    private fun withoutSourceList(reply: String): String {
        val lines = reply.trimEnd().lines()
        val last = lines.lastOrNull() ?: return reply
        if (inlineSources.matches(last)) return lines.dropLast(1).joinToString("\n")
        val header = lines.indexOfLast { sourcesHeader.matches(it) }
        if (header <= 0) return reply
        val after = lines.drop(header + 1).filter(String::isNotBlank)
        if (after.isEmpty() || !after.all(listItem::matches)) return reply
        return lines.take(header).joinToString("\n")
    }
}
