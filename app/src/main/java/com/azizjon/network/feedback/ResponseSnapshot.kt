package com.azizjon.network.feedback

import com.azizjon.network.ai.ActionState
import com.azizjon.network.ai.ChatAttachment
import com.azizjon.network.ai.ChatMessage
import com.azizjon.network.data.AiFeedbackEntity

/**
 * Flattens an assistant response into the plain text a report stores.
 *
 * A report has to stand on its own. The card under a message is live Compose
 * state that disappears when the thread is cleared, so the moment the user
 * reports it, everything worth analysing is copied out as text. Reading it
 * later needs no app, no database, and no chat thread.
 *
 * Contact values never make it out: tool calls are recorded with any contact
 * argument replaced before they reach the card, because a report is meant to
 * be shareable and a phone number is not a fault description.
 */
object ResponseSnapshot {
    /** Which kind of reply [message] was, for grouping in the report. */
    fun stageOf(message: ChatMessage): String = when {
        message.failed -> AiFeedbackEntity.Stage.ERROR
        message.attachment is ChatAttachment.AgentResult -> AiFeedbackEntity.Stage.AGENT
        else -> AiFeedbackEntity.Stage.MESSAGE
    }

    /** The card under [message] as readable text, or empty when it carries none. */
    fun detailOf(message: ChatMessage): String =
        when (val attachment = message.attachment) {
            is ChatAttachment.AgentResult -> describeAgentResult(attachment)
            null -> ""
        }

    /**
     * Everything the reply did, with the tool calls that did it.
     *
     * The calls are the point: a note filed on the wrong person shows up as the
     * find_people query that matched the wrong name, which the saved lines alone
     * would never reveal.
     */
    private fun describeAgentResult(result: ChatAttachment.AgentResult): String = buildString {
        section("Saved", result.saved)
        if (result.undone) appendLine("Undone by the user afterwards.")
        result.undoError?.let { appendLine("Undo was refused: $it") }
        section(
            "Waiting for confirmation",
            result.pending.map { item ->
                val state = when (item.state) {
                    ActionState.PENDING -> "not answered"
                    ActionState.DONE -> "confirmed"
                    ActionState.KEPT -> "declined"
                    ActionState.FAILED -> "failed: ${item.outcome}"
                }
                "${item.action.description} [$state]"
            },
        )
        section("Tool calls, in order", result.calls)
    }.trim()

    /** Skips a heading entirely when nothing under it happened. */
    private fun StringBuilder.section(title: String, values: List<String>) {
        if (values.isEmpty()) return
        appendLine()
        appendLine("$title:")
        values.forEach { appendLine("- $it") }
    }
}

/** Builds the row stored when the user reports [message] as wrong. */
fun buildFeedback(
    message: ChatMessage,
    userMessage: String,
    label: AiFeedbackLabel,
    note: String,
    appVersion: String,
    now: Long = System.currentTimeMillis(),
): AiFeedbackEntity = AiFeedbackEntity(
    stage = ResponseSnapshot.stageOf(message),
    label = label.id,
    note = note,
    userMessage = userMessage,
    assistantMessage = message.text,
    assistantDetail = ResponseSnapshot.detailOf(message),
    appVersion = appVersion,
    createdAt = now,
)
