package com.azizjon.network.ai

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPromptTest {
    @Test
    fun onlyTheConversationTravelsNotTheAppsOwnLines() {
        val history = AssistantPrompt.history(
            listOf(
                message(1, ChatRole.USER, "Ana joined Northwind Labs"),
                message(2, ChatRole.ASSISTANT, "Saved.", fromGateway = true, memory = listOf("added position:4 to Ana Lee (person 3)")),
                message(3, ChatRole.ASSISTANT, "Undone. Everything that reply saved is back the way it was."),
                message(4, ChatRole.ASSISTANT, "The gateway timed out.", fromGateway = true, failed = true),
                message(5, ChatRole.USER, "Actually she is only advising them"),
            ),
        )

        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER), history.map { it.role })
        // A follow-up correction needs the address of the record it corrects.
        assertTrue(history[1].text.contains("position:4"))
        assertFalse(history.any { it.text.contains("timed out") || it.text.contains("Undone") })
    }

    @Test
    fun longThreadsKeepOnlyTheMostRecentTurns() {
        val many = (1..20).map { message(it.toLong(), ChatRole.USER, "turn $it " + "x".repeat(600)) }

        val history = AssistantPrompt.history(many)

        assertTrue(history.size <= AssistantPrompt.MAX_TURNS)
        assertTrue(history.sumOf { it.text.length } <= AssistantPrompt.MAX_HISTORY_CHARACTERS)
        assertTrue(history.last().text.startsWith("turn 20 "))
    }

    @Test
    fun theInputGivesTheMomentThenTheContextThenTheMessage() {
        val input = AssistantPrompt.input(
            message = "  Who could help Ana with fundraising?  ",
            history = listOf(ChatTurn(ChatRole.USER, "Ana joined Northwind Labs")),
            now = Instant.parse("2026-09-14T12:30:00Z"),
            zone = ZoneId.of("Asia/Seoul"),
            locale = "en-US",
        )

        assertTrue(input.startsWith("Now: 2026-09-14 21:30"))
        assertTrue(input.contains("time zone Asia/Seoul"))
        assertTrue(input.indexOf("User: Ana joined Northwind Labs") < input.indexOf("The user's message:"))
        assertTrue(input.endsWith("The user's message:\nWho could help Ana with fundraising?"))
    }

    private fun message(
        id: Long,
        role: ChatRole,
        text: String,
        fromGateway: Boolean = false,
        failed: Boolean = false,
        memory: List<String> = emptyList(),
    ) = ChatMessage(
        id = id,
        role = role,
        text = text,
        attachment = if (memory.isEmpty()) {
            null
        } else {
            ChatAttachment.AgentResult(
                saved = listOf("Added a position"),
                changes = emptyList(),
                memory = memory,
                pending = emptyList(),
                people = emptyList(),
                calls = emptyList(),
            )
        },
        failed = failed,
        sentAt = id,
        fromGateway = fromGateway,
    )
}
