package com.azizjon.network.checkin

import java.text.Normalizer
import java.util.Locale

/** The chat apps whose notifications say who wrote. Package names, by source. */
object ChatSources {
    const val TELEGRAM = "telegram"
    const val WHATSAPP = "whatsapp"
    const val KAKAOTALK = "kakaotalk"
    const val LINKEDIN = "linkedin"

    private val PACKAGES = mapOf(
        "org.telegram.messenger" to TELEGRAM,
        "org.telegram.messenger.web" to TELEGRAM,
        "org.thunderdog.challegram" to TELEGRAM,
        "com.whatsapp" to WHATSAPP,
        "com.whatsapp.w4b" to WHATSAPP,
        "com.kakao.talk" to KAKAOTALK,
        "com.linkedin.android" to LINKEDIN,
    )

    /** Debug builds also accept `adb shell cmd notification post`, which posts as the shell. */
    private const val SHELL = "com.android.shell"

    fun sourceFor(packageName: String, debuggable: Boolean): String? =
        PACKAGES[packageName] ?: if (debuggable && packageName == SHELL) TELEGRAM else null

    fun label(source: String): String = when (source) {
        TELEGRAM -> "Telegram"
        WHATSAPP -> "WhatsApp"
        KAKAOTALK -> "KakaoTalk"
        LINKEDIN -> "LinkedIn"
        CheckinEntity.SOURCE_CONTACTS -> "your phone's contacts"
        else -> source
    }
}

/**
 * What the listener takes from one posted notification, already stripped to
 * what the parser may use. [text] is read only to spot a LinkedIn connection
 * and is never stored.
 */
data class PostedChat(
    val source: String,
    val category: String?,
    val isGroupSummary: Boolean,
    val isGroupConversation: Boolean,
    val title: String?,
    /** Who wrote each message in a messaging-style notification, the user's own left out. */
    val senders: List<String>,
    val text: String?,
)

/** Someone who wrote, as the chat app named them. */
data class Sighting(val source: String, val name: String)

/**
 * Works out who wrote from a chat app's notification.
 *
 * Only one-to-one conversations count: a group would put every member of every
 * busy group on the list. Messaging-style notifications name each sender; for
 * the rest, a message notification's title is the sender. LinkedIn also posts
 * likes, job alerts and the like, so there only messages and connections count.
 */
object ChatNotificationParser {
    fun parse(posted: PostedChat): List<Sighting> {
        if (posted.isGroupSummary || posted.isGroupConversation) return emptyList()
        val names = when {
            posted.senders.isNotEmpty() -> posted.senders
            posted.source == ChatSources.LINKEDIN -> listOfNotNull(linkedInName(posted))
            posted.category == null || posted.category == CATEGORY_MESSAGE -> listOfNotNull(posted.title)
            else -> emptyList()
        }
        return names.map(::cleanName)
            .filter { it.isNotEmpty() && !looksLikeASummary(it) && it.lowercase(Locale.ROOT) != ChatSources.label(posted.source).lowercase(Locale.ROOT) }
            .distinct()
            .map { Sighting(posted.source, it) }
    }

    private fun linkedInName(posted: PostedChat): String? {
        if (posted.category == CATEGORY_MESSAGE) return posted.title
        // "Ana Lee accepted your invitation", "Ana Lee wants to connect", in the title or the text.
        for (line in listOfNotNull(posted.title, posted.text)) {
            CONNECTION_PATTERNS.firstNotNullOfOrNull { it.find(line) }?.let { return it.groupValues[1] }
        }
        // Or the name as the title and "accepted your invitation" as the text.
        val text = posted.text?.trim().orEmpty()
        return posted.title?.takeIf { CONNECTION_WITHOUT_NAME.any { it.containsMatchIn(text) } }
    }

    /** "Ana Lee (2 messages)" and "Ana Lee: " are still Ana Lee. */
    private fun cleanName(raw: String): String =
        raw.replace(MESSAGE_COUNT, "").trim().trimEnd(':').trim()

    /** "3 new messages", "WhatsApp" and the like are not a person. */
    private fun looksLikeASummary(name: String): Boolean =
        SUMMARY.matches(name) || NameKey.of(name).isEmpty()

    private const val CATEGORY_MESSAGE = "msg"
    private val MESSAGE_COUNT = Regex("""\s*\(\d+\s+\w+\)\s*$""")
    private val SUMMARY = Regex("""^\d+\s+(new\s+)?(messages?|chats?)\b.*""", RegexOption.IGNORE_CASE)
    // English and Korean wording; LinkedIn's notifications carry no structured sender.
    private val CONNECTION_PATTERNS = listOf(
        Regex("""^(.+?)\s+accepted your invitation""", RegexOption.IGNORE_CASE),
        Regex("""^(.+?)\s+wants to connect""", RegexOption.IGNORE_CASE),
        Regex("""^(.+?)\s+(sent you an invitation|invited you to connect)""", RegexOption.IGNORE_CASE),
        Regex("""^(.+?)\s*님이\s*1촌"""),
    )
    private val CONNECTION_WITHOUT_NAME = listOf(
        Regex("""^(accepted your invitation|wants to connect|sent you an invitation|invited you to connect)""", RegexOption.IGNORE_CASE),
        Regex("""^1촌"""),
    )
}

/**
 * The form of a name used to tell whether two names are the same person:
 * case, accents, punctuation, emoji, a bracketed aside, and word order are
 * ignored, so "Kim Minsu", "minsu kim" and "Minsu Kim 🚀 (Lumen)" all match.
 */
object NameKey {
    fun of(name: String): String {
        val withoutAccents = Normalizer.normalize(name.replace(BRACKETED, " "), Normalizer.Form.NFKD).replace(COMBINING_MARKS, "")
        // Recomposed, or Hangul would stay split into its letters.
        val plain = Normalizer.normalize(withoutAccents, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        return plain.split(NOT_A_LETTER).filter(String::isNotEmpty).sorted().joinToString(" ")
    }

    private val BRACKETED = Regex("""\([^)]*\)|\[[^]]*]""")
    private val COMBINING_MARKS = Regex("""\p{Mn}+""")
    private val NOT_A_LETTER = Regex("""[^\p{L}\p{N}]+""")
}
