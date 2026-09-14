package com.azizjon.network.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentClientTest {
    private val turn = "6f202642-e4f3-4986-b500-270e98e7cd60"

    @Test
    fun aToolCallCarriesItsIdNameAndArguments() {
        val event = AgentClient.parseEvent(
            """{"turn_id":"$turn","status":"tool_call","call":{"id":"call_2","name":"get_person","input":{"person_id":7}}}""",
        )

        assertTrue(event is AgentEvent.ToolCall)
        event as AgentEvent.ToolCall
        assertEquals(turn, event.turnId)
        assertEquals("call_2", event.callId)
        assertEquals("get_person", event.name)
        assertEquals(7L, event.input.getLong("person_id"))
    }

    @Test
    fun aFinishedTurnCarriesTheReply() {
        val event = AgentClient.parseEvent(
            """{"turn_id":"$turn","status":"done","text":" Saved it. ","model":"m","tool_calls":3,"duration_ms":7726}""",
        )

        event as AgentEvent.Done
        assertEquals("Saved it.", event.text)
        assertEquals(3, event.toolCalls)
        assertEquals(7726L, event.durationMs)
    }

    @Test
    fun anythingElseIsRefusedRatherThanGuessedAt() {
        listOf(
            "not json",
            """{"status":"done","text":"no turn id"}""",
            """{"turn_id":"../../etc","status":"done","text":"x"}""",
            """{"turn_id":"$turn","status":"thinking"}""",
            """{"turn_id":"$turn","status":"tool_call","call":{"id":"call_1"}}""",
        ).forEach { body ->
            assertThrows(body, GatewayException::class.java) { AgentClient.parseEvent(body) }
        }
    }

    @Test
    fun gatewayFailuresBecomeSomethingTheUserCanActOn() {
        assertEquals("The access token is invalid. Replace it in Settings.", AgentClient.httpFailureReason(401))
        assertTrue(AgentClient.httpFailureReason(429, """{"error":{"code":"upstream_rate_limited"}}""").contains("usage limit"))
        assertTrue(AgentClient.httpFailureReason(429, """{"error":{"code":"queue_full"}}""").contains("busy"))
        assertTrue(AgentClient.httpFailureReason(502, """{"error":{"code":"agent_max_steps"}}""").contains("too many steps"))
        assertTrue(AgentClient.httpFailureReason(504, """{"error":{"code":"tool_result_timeout"}}""").contains("stopped waiting"))
        assertTrue(AgentClient.httpFailureReason(404, """{"error":{"code":"unknown_turn"}}""").contains("lost track"))
        assertTrue(AgentClient.httpFailureReason(503, """{"error":{"code":"agent_unavailable"}}""").contains("cannot run"))
        assertTrue(AgentClient.httpFailureReason(504).contains("too long"))
        assertEquals("The gateway returned HTTP 418.", AgentClient.httpFailureReason(418))
    }
}
