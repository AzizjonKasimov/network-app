package com.azizjon.network.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatNotificationParserTest {
    @Test
    fun aMessagingNotificationNamesEachSenderOnce() {
        val sightings = ChatNotificationParser.parse(
            chat(ChatSources.TELEGRAM, title = "Ana Lee", senders = listOf("Ana Lee", "Ana Lee")),
        )

        assertEquals(listOf(Sighting(ChatSources.TELEGRAM, "Ana Lee")), sightings)
    }

    @Test
    fun groupsAndSummariesAreSkipped() {
        assertEquals(emptyList<Sighting>(), ChatNotificationParser.parse(chat(ChatSources.WHATSAPP, senders = listOf("Ana Lee"), group = true)))
        assertEquals(emptyList<Sighting>(), ChatNotificationParser.parse(chat(ChatSources.WHATSAPP, title = "Ana Lee", summary = true)))
    }

    @Test
    fun withoutMessagingStyleTheTitleOfAMessageIsTheSender() {
        assertEquals(
            listOf(Sighting(ChatSources.KAKAOTALK, "Kim Minsu")),
            ChatNotificationParser.parse(chat(ChatSources.KAKAOTALK, title = "Kim Minsu (2 messages)", category = "msg")),
        )
        // Some other kind of notification from the same app is not a person.
        assertEquals(emptyList<Sighting>(), ChatNotificationParser.parse(chat(ChatSources.KAKAOTALK, title = "Kim Minsu", category = "promo")))
    }

    @Test
    fun countsAndTheAppsOwnNameAreNotPeople() {
        assertEquals(emptyList<Sighting>(), ChatNotificationParser.parse(chat(ChatSources.TELEGRAM, title = "3 new messages", category = "msg")))
        assertEquals(emptyList<Sighting>(), ChatNotificationParser.parse(chat(ChatSources.WHATSAPP, title = "WhatsApp", category = "msg")))
        assertEquals(emptyList<Sighting>(), ChatNotificationParser.parse(chat(ChatSources.TELEGRAM, title = "🎉", category = "msg")))
    }

    @Test
    fun linkedInCountsMessagesAndConnectionsButNothingElse() {
        assertEquals(
            listOf(Sighting(ChatSources.LINKEDIN, "Ana Lee")),
            ChatNotificationParser.parse(chat(ChatSources.LINKEDIN, title = "LinkedIn", text = "Ana Lee accepted your invitation. Say hello.")),
        )
        assertEquals(
            listOf(Sighting(ChatSources.LINKEDIN, "Ben Ortiz")),
            ChatNotificationParser.parse(chat(ChatSources.LINKEDIN, title = "Ben Ortiz wants to connect")),
        )
        assertEquals(
            listOf(Sighting(ChatSources.LINKEDIN, "Ana Lee")),
            ChatNotificationParser.parse(chat(ChatSources.LINKEDIN, title = "Ana Lee", category = "msg")),
        )
        assertEquals(
            emptyList<Sighting>(),
            ChatNotificationParser.parse(chat(ChatSources.LINKEDIN, title = "Ana Lee", text = "Ana Lee reacted to your post")),
        )
        // The name as the title, the event as the text.
        assertEquals(
            listOf(Sighting(ChatSources.LINKEDIN, "Ana Lee")),
            ChatNotificationParser.parse(chat(ChatSources.LINKEDIN, title = "Ana Lee", text = "accepted your invitation")),
        )
        // LinkedIn in Korean.
        assertEquals(
            listOf(Sighting(ChatSources.LINKEDIN, "김민수")),
            ChatNotificationParser.parse(chat(ChatSources.LINKEDIN, title = "LinkedIn", text = "김민수님이 1촌 신청을 수락했습니다.")),
        )
    }

    @Test
    fun aKakaoGroupChatIsSkippedButADirectChatIsNot() {
        // KakaoTalk posts messaging-style notifications and marks groups as group conversations.
        assertEquals(emptyList<Sighting>(), ChatNotificationParser.parse(chat(ChatSources.KAKAOTALK, title = "Kim Minsu", senders = listOf("Kim Minsu"), group = true)))
        assertEquals(
            listOf(Sighting(ChatSources.KAKAOTALK, "Kim Minsu")),
            ChatNotificationParser.parse(chat(ChatSources.KAKAOTALK, title = "Kim Minsu", senders = listOf("Kim Minsu"))),
        )
    }

    @Test
    fun onlyKnownChatAppsHaveASource() {
        assertEquals(ChatSources.KAKAOTALK, ChatSources.sourceFor("com.kakao.talk", debuggable = false))
        assertNull(ChatSources.sourceFor("com.example.bank", debuggable = false))
        // The shell posts test notifications in debug builds only.
        assertNull(ChatSources.sourceFor("com.android.shell", debuggable = false))
        assertEquals(ChatSources.TELEGRAM, ChatSources.sourceFor("com.android.shell", debuggable = true))
    }

    private fun chat(
        source: String,
        title: String? = null,
        senders: List<String> = emptyList(),
        category: String? = null,
        group: Boolean = false,
        summary: Boolean = false,
        text: String? = null,
    ) = PostedChat(
        source = source,
        category = category,
        isGroupSummary = summary,
        isGroupConversation = group,
        title = title,
        senders = senders,
        text = text,
    )
}
