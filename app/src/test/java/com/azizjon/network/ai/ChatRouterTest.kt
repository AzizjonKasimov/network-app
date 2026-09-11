package com.azizjon.network.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRouterTest {
    @Test
    fun captureScopedHistoryDropsWholeNetworkAnswers() {
        // Capture is scoped to one person. A search answer names people from the
        // whole network, so replaying it would widen a request the user only ever
        // approved as single-person.
        val messages = listOf(
            user(1, "Met Synthetic Alex at the meetup."),
            assistant(2, "Prepared changes for Synthetic Alex."),
            user(3, "Who could help with logistics?"),
            assistant(
                4,
                "Two people match.",
                ChatAttachment.Search(emptyList()),
            ),
            user(5, "Alex also mentioned a new role."),
        )

        val captureTurns = ChatRouter.captureScopedTurns(messages)

        assertTrue(captureTurns.none { it.text == "Two people match." })
        assertEquals(
            listOf(
                "Met Synthetic Alex at the meetup.",
                "Prepared changes for Synthetic Alex.",
                "Who could help with logistics?",
                "Alex also mentioned a new role.",
            ),
            captureTurns.map { it.text },
        )
    }

    @Test
    fun reFilingRequestsSkipTheOfflineShortcut() {
        // "Move Alex's note to Wren" names a person who may not be saved yet, so
        // only one name is recognisable and the shortcut would file it as a fresh
        // note about Alex instead of moving hers.
        assertTrue(ChatRouter.looksLikeMove("Move Alex's note to Wren"))
        assertTrue(ChatRouter.looksLikeMove("that belongs to Robin, not Alex"))
        assertTrue(ChatRouter.looksLikeMove("Wrong person - refile it"))
        assertTrue(ChatRouter.looksLikeMove("that note should be under Robin"))

        // An ordinary capture must not be dragged into the move path by a word
        // that merely looks like one.
        assertFalse(ChatRouter.looksLikeMove("Met Alex at the logistics meetup."))
        assertFalse(ChatRouter.looksLikeMove("Alex is removing herself from the board."))
        assertFalse(ChatRouter.looksLikeMove(""))
    }

    @Test
    fun searchScopedHistoryKeepsEverything() {
        // Search sends every active person in the same request, so earlier turns
        // disclose nothing the corpus does not already carry.
        val messages = listOf(
            user(1, "Who knows about logistics?"),
            assistant(2, "Two people match.", ChatAttachment.Search(emptyList())),
        )

        assertEquals(2, ChatRouter.searchScopedTurns(messages).size)
    }

    @Test
    fun historyIsBoundedByTurnCountAndCharacters() {
        val many = (1..20).map { user(it.toLong(), "Turn $it") }

        assertEquals(ChatRouter.MAX_TURNS, ChatRouter.captureScopedTurns(many).size)
        assertEquals("Turn 20", ChatRouter.captureScopedTurns(many).last().text)

        val long = (1..4).map { user(it.toLong(), "x".repeat(900)) }
        val trimmed = ChatRouter.captureScopedTurns(long)

        assertTrue(trimmed.sumOf { it.text.length } <= ChatRouter.MAX_HISTORY_CHARACTERS)
    }

    @Test
    fun blankTurnsAreNeverReplayed() {
        val messages = listOf(user(1, "Real turn."), assistant(2, "   "))

        assertEquals(listOf("Real turn."), ChatRouter.captureScopedTurns(messages).map { it.text })
    }

    @Test
    fun questionsAreRecognisedSoTheOfflineNameShortcutIsNotTaken() {
        assertTrue(ChatRouter.looksLikeQuestion("Who could help Synthetic Alex with funding?"))
        assertTrue(ChatRouter.looksLikeQuestion("anyone in logistics"))
        assertTrue(ChatRouter.looksLikeQuestion("Does anyone know a designer"))
        assertTrue(ChatRouter.looksLikeQuestion("Find me a mentor"))

        assertFalse(ChatRouter.looksLikeQuestion("Met Synthetic Alex today, he is hiring."))
        assertFalse(ChatRouter.looksLikeQuestion("Synthetic Alex wants an intro to a designer"))
        assertFalse(ChatRouter.looksLikeQuestion(""))
    }

    private fun user(id: Long, text: String) =
        ChatMessage(id = id, role = ChatRole.USER, text = text, sentAt = id)

    private fun assistant(id: Long, text: String, attachment: ChatAttachment? = null) =
        ChatMessage(id = id, role = ChatRole.ASSISTANT, text = text, attachment = attachment, sentAt = id)
}
