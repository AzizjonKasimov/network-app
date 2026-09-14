package com.azizjon.network.ai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GatewayException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The next thing an agent turn needs from the phone. */
sealed interface AgentEvent {
    val turnId: String

    /** Run [name] with [input] and post the result. */
    data class ToolCall(
        override val turnId: String,
        val callId: String,
        val name: String,
        val input: JSONObject,
    ) : AgentEvent

    /** The turn is over. [text] is the reply for the thread. */
    data class Done(
        override val turnId: String,
        val text: String,
        val toolCalls: Int,
        val durationMs: Long,
    ) : AgentEvent
}

/**
 * Speaks the gateway's agent protocol: start a turn, answer each tool call.
 *
 * Every exchange is one ordinary POST. The response is either a tool call for
 * the phone to run or the finished reply, so there is no socket to keep open
 * and nothing to resume: a dropped exchange simply fails the turn.
 */
class AgentClient(private val tokenProvider: () -> String?) {
    val configured: Boolean
        get() = !tokenProvider().isNullOrBlank()

    suspend fun start(system: String, input: String, tools: JSONArray, maxSteps: Int): AgentEvent =
        parseEvent(
            post(
                "$GATEWAY_BASE_URL/v1/agent/turns",
                JSONObject()
                    .put("system", system)
                    .put("input", input)
                    .put("tools", tools)
                    .put("max_steps", maxSteps),
            ),
        )

    suspend fun submit(turnId: String, callId: String, output: String, isError: Boolean): AgentEvent =
        parseEvent(
            post(
                "$GATEWAY_BASE_URL/v1/agent/turns/${checkedTurnId(turnId)}/results",
                JSONObject()
                    .put("call_id", callId)
                    .put("output", output.take(MAX_TOOL_OUTPUT_CHARACTERS))
                    .put("is_error", isError),
            ),
        )

    /** Best effort. A turn the gateway already ended has nothing to stop. */
    suspend fun cancel(turnId: String) {
        runCatching { post("$GATEWAY_BASE_URL/v1/agent/turns/${checkedTurnId(turnId)}/cancel", JSONObject()) }
    }

    private suspend fun post(url: String, body: JSONObject): String = withContext(Dispatchers.IO) {
        val token = tokenProvider()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw GatewayException("Add an access token in Settings first.")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $token")
        }
        // A turn is a burst of requests to the same host. Leaving a cleanly read
        // connection open lets the next one skip a fresh TLS handshake, which
        // from the phone costs more than the tool call itself.
        var reusable = false
        try {
            connection.outputStream.use { output -> output.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NO_CONTENT) return@withContext ""
            val response = readLimited(
                if (status in 200..299) connection.inputStream else connection.errorStream,
                MAX_RESPONSE_BYTES,
            )
            if (status !in 200..299) throw GatewayException(httpFailureReason(status, response))
            reusable = true
            response
        } catch (error: GatewayException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw GatewayException("The assistant stopped responding. Anything it already saved is listed below.", error)
        } catch (error: IOException) {
            throw GatewayException("Could not reach the assistant. Check your connection and try again.", error)
        } finally {
            if (!reusable) connection.disconnect()
        }
    }

    companion object {
        const val MAX_INPUT_CHARACTERS = 4_000

        /** Most model steps one message may take. The gateway caps it too. */
        const val MAX_STEPS = 30

        const val MAX_TOOL_OUTPUT_CHARACTERS = 90_000
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val CONNECT_TIMEOUT_MILLIS = 15_000

        /** One model step, which can be a long reply. Caddy gives up at 180 s. */
        const val READ_TIMEOUT_MILLIS = 170_000

        /**
         * Private AI gateway. The model is chosen by the gateway, so changing it
         * does not require an app release.
         */
        internal const val GATEWAY_BASE_URL = "https://ai.204-168-198-233.sslip.io"

        private val TURN_ID = Regex("^[0-9a-f-]{36}$")

        internal fun parseEvent(responseBody: String): AgentEvent {
            val json = runCatching { JSONObject(responseBody) }
                .getOrElse { throw GatewayException("The assistant returned an unreadable response.", it) }
            val turnId = json.optString("turn_id")
            if (!TURN_ID.matches(turnId)) throw GatewayException("The assistant returned an unreadable response.")
            return when (json.optString("status")) {
                "tool_call" -> {
                    val call = json.optJSONObject("call")
                        ?: throw GatewayException("The assistant asked for a tool without saying which.")
                    val name = call.optString("name")
                    val callId = call.optString("id")
                    if (name.isBlank() || callId.isBlank()) throw GatewayException("The assistant asked for a tool without saying which.")
                    AgentEvent.ToolCall(turnId, callId, name, call.optJSONObject("input") ?: JSONObject())
                }
                "done" -> AgentEvent.Done(
                    turnId = turnId,
                    text = json.optString("text").trim(),
                    toolCalls = json.optInt("tool_calls"),
                    durationMs = json.optLong("duration_ms"),
                )
                else -> throw GatewayException("The assistant returned an unreadable response.")
            }
        }

        /**
         * Turns a gateway failure into something the user can act on. The body is
         * matched as text rather than parsed so this stays a pure function that
         * unit tests can exercise without org.json.
         */
        internal fun httpFailureReason(status: Int, responseBody: String = ""): String = when {
            status == 401 -> "The access token is invalid. Replace it in Settings."
            responseBody.contains("queue_full", ignoreCase = true) ->
                "The assistant is busy right now. Try again in a moment."
            responseBody.contains("upstream_rate_limited", ignoreCase = true) || status == 429 ->
                "The Claude usage limit was reached. Try again later."
            responseBody.contains("agent_max_steps", ignoreCase = true) ->
                "That took the assistant too many steps. Try asking for less at once."
            responseBody.contains("tool_result_timeout", ignoreCase = true) ->
                "The assistant stopped waiting for this phone. Try again."
            responseBody.contains("agent_unavailable", ignoreCase = true) ->
                "The gateway cannot run the assistant right now."
            responseBody.contains("unknown_turn", ignoreCase = true) ->
                "The assistant lost track of this request. Try again."
            status == 422 -> "The assistant declined this request. Try different wording."
            status == 400 -> "The gateway rejected the request. Update the app."
            status == 409 -> "The request was stopped before it finished."
            status == 504 -> "The assistant took too long to answer. Try again."
            status in 500..599 -> "The assistant is unavailable. Try again later."
            else -> "The gateway returned HTTP $status."
        }

        private fun checkedTurnId(turnId: String): String {
            if (!TURN_ID.matches(turnId)) throw GatewayException("The assistant returned an unreadable response.")
            return turnId
        }

        private fun readLimited(input: InputStream?, maximumBytes: Int): String {
            if (input == null) return ""
            return input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximumBytes) throw GatewayException("The assistant response was too large to read safely.")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        }
    }
}
