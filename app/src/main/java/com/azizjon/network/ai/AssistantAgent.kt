package com.azizjon.network.ai

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How a turn ended. A failed turn still carries everything it saved before it
 * failed, so the thread can show it and offer to undo it.
 */
data class AgentOutcome(
    val reply: String?,
    val log: AgentTurnLog,
    val error: String?,
)

/**
 * Runs one message through the agent: Claude plans on the gateway, the tools
 * run here, one exchange per tool call, until Claude answers.
 */
class AssistantAgent(
    private val client: AgentClient,
    private val tools: AssistantTools,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    val configured: Boolean get() = client.configured

    suspend fun run(message: String, history: List<ChatTurn>, onProgress: (String) -> Unit): AgentOutcome {
        val log = AgentTurnLog()
        var turnId: String? = null
        return try {
            var event = client.start(
                system = AssistantPrompt.SYSTEM,
                input = AssistantPrompt.input(message, history, clock(), zone(), Locale.getDefault().toLanguageTag()),
                tools = tools.definitions(),
                maxSteps = AgentClient.MAX_STEPS,
            )
            turnId = event.turnId
            while (event is AgentEvent.ToolCall) {
                val call: AgentEvent.ToolCall = event
                onProgress(tools.progressLabel(call.name, call.input))
                val result = tools.execute(call.name, call.input, log)
                event = client.submit(call.turnId, call.callId, result.output, result.isError)
            }
            val done = event as AgentEvent.Done
            AgentOutcome(
                reply = done.text.ifBlank { if (log.saved.isEmpty()) "Done." else "Saved." },
                log = log,
                error = null,
            )
        } catch (cancelled: CancellationException) {
            // Leaving the turn running would keep Claude working on a reply
            // nobody will read.
            turnId?.let { id -> withContext(NonCancellable) { withTimeoutOrNull(CANCEL_TIMEOUT_MILLIS) { client.cancel(id) } } }
            throw cancelled
        } catch (error: GatewayException) {
            AgentOutcome(reply = null, log = log, error = error.message ?: "The assistant could not finish.")
        } catch (error: Exception) {
            turnId?.let { id -> withTimeoutOrNull(CANCEL_TIMEOUT_MILLIS) { client.cancel(id) } }
            AgentOutcome(reply = null, log = log, error = error.message ?: "The assistant could not finish.")
        }
    }

    private companion object {
        const val CANCEL_TIMEOUT_MILLIS = 5_000L
    }
}
