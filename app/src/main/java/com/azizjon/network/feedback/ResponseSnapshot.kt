package com.azizjon.network.feedback

import com.azizjon.network.ai.AiPersonSearchResult
import com.azizjon.network.ai.ChatAttachment
import com.azizjon.network.ai.ChatMessage
import com.azizjon.network.data.AiFeedbackEntity
import com.azizjon.network.data.AiWriteProposal
import com.azizjon.network.data.ProfileField
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Flattens an assistant response into the plain text a report stores.
 *
 * A report has to stand on its own. The card under a message is live Compose
 * state that disappears when the proposal is applied or the thread is cleared,
 * so the moment the user reports it, everything worth analysing is copied out
 * as text. Reading it later needs no app, no database, and no chat thread.
 *
 * Contact values never make it out: a contact patch is recorded as having
 * happened without its value, because a report is meant to be shareable and a
 * phone number is not a fault description.
 */
object ResponseSnapshot {
    const val CONTACT_PLACEHOLDER = "<contact value omitted from reports>"

    /** Which assistant step produced [message], for grouping in the report. */
    fun stageOf(message: ChatMessage): String = when {
        message.failed -> AiFeedbackEntity.Stage.ERROR
        message.attachment is ChatAttachment.Proposal -> AiFeedbackEntity.Stage.PROPOSAL
        message.attachment is ChatAttachment.Search -> AiFeedbackEntity.Stage.SEARCH
        message.attachment is ChatAttachment.TargetChoice -> AiFeedbackEntity.Stage.TARGET_CHOICE
        else -> AiFeedbackEntity.Stage.MESSAGE
    }

    /** The card under [message] as readable text, or empty when it carries none. */
    fun detailOf(message: ChatMessage, zoneId: ZoneId = ZoneId.systemDefault()): String =
        when (val attachment = message.attachment) {
            is ChatAttachment.Proposal -> describeProposal(attachment, zoneId)
            is ChatAttachment.Search -> describeSearch(attachment.results)
            is ChatAttachment.TargetChoice -> buildString {
                appendLine("Asked which person was meant.")
                appendLine("Name the assistant resolved: ${attachment.value.targetName}")
                appendLine("Offered: " + attachment.value.suggestions.joinToString(", ") { it.name })
            }.trim()
            null -> ""
        }

    private fun describeProposal(attachment: ChatAttachment.Proposal, zoneId: ZoneId): String {
        val proposal = attachment.proposal
        return buildString {
            appendLine("Proposed changes for: ${proposal.targetName}")
            appendLine(
                "Target: " + if (proposal.targetPersonId == null) {
                    "new person"
                } else {
                    "existing person id ${proposal.targetPersonId}"
                },
            )
            appendLine("Interaction date: ${formatDate(proposal.occurredAt, zoneId)}")
            appendLine("Applied by the user: ${attachment.applied}")
            attachment.caveat?.takeIf { it.isNotBlank() }?.let { appendLine("Caveat shown: $it") }
            section("Profile changes", proposal.profilePatches.map { patch ->
                val value = if (patch.field == ProfileField.CONTACT) CONTACT_PLACEHOLDER else patch.value
                "${patch.field.name.lowercase()} -> $value" + selection(patch.selected)
            })
            section("New needs", proposal.newNeeds.map { it.text + selection(it.selected) })
            section("New capabilities", proposal.newCapabilities.map { it.text + selection(it.selected) })
            section("New positions", proposal.newAffiliations.map { item ->
                buildString {
                    append(positionLabel(item.role, item.organization))
                    append(if (item.education) " [education]" else " [work]")
                    append(if (item.current) " [current]" else " [past]")
                    append(selection(item.selected))
                }
            })
            section("New background facts", proposal.newFacts.map { it.text + selection(it.selected) })
            section("Edited interactions", proposal.interactionEdits.map { edit ->
                "id ${edit.id} on ${formatDate(edit.occurredAt, zoneId)}: ${edit.note}" + selection(edit.selected)
            })
            section("Edited needs", proposal.needEdits.map { edit ->
                "id ${edit.id} [${edit.status}]: ${edit.text}" + selection(edit.selected)
            })
            section("Edited capabilities", proposal.capabilityEdits.map { edit ->
                "id ${edit.id} [${if (edit.active) "active" else "inactive"}]: ${edit.text}" + selection(edit.selected)
            })
            section("Edited positions", proposal.affiliationEdits.map { edit ->
                buildString {
                    append("id ${edit.id}: ${positionLabel(edit.role, edit.organization)}")
                    append(if (edit.education) " [education]" else " [work]")
                    append(if (edit.current) " [current]" else " [past]")
                    append(selection(edit.selected))
                }
            })
            section("Edited background facts", proposal.factEdits.map { edit ->
                "id ${edit.id}: ${edit.text}" + selection(edit.selected)
            })
            section("Left in the interaction only", proposal.interactionOnlyFacts)
            appendLine()
            appendLine("Original message stored verbatim as the interaction note:")
            append(proposal.rawInput)
        }.trim()
    }

    private fun describeSearch(results: List<AiPersonSearchResult>): String = buildString {
        appendLine("Returned ${results.size} candidate(s).")
        results.forEachIndexed { index, result ->
            appendLine()
            appendLine("${index + 1}. ${result.person.name} (person id ${result.person.id})")
            appendLine("   Reasoning: ${result.reasoning}")
            appendLine("   Uncertainty: ${result.uncertainty}")
            result.evidence.forEach { evidence ->
                appendLine("   Evidence [${evidence.kind} ${evidence.id}]: ${evidence.text}")
            }
        }
    }.trim()

    /** Skips a heading entirely when nothing under it changed. */
    private fun StringBuilder.section(title: String, values: List<String>) {
        if (values.isEmpty()) return
        appendLine()
        appendLine("$title:")
        values.forEach { appendLine("- $it") }
    }

    /** Unticked rows matter: they show what the user refused before applying. */
    private fun selection(selected: Boolean): String = if (selected) "" else " [unticked]"

    private fun positionLabel(role: String, organization: String): String = when {
        organization.isBlank() -> role
        role.isBlank() -> organization
        else -> "$role at $organization"
    }

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private fun formatDate(timestamp: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(timestamp).atZone(zoneId).format(dateFormatter)
}

/** Builds the row stored when the user reports [message] as wrong. */
fun buildFeedback(
    message: ChatMessage,
    userMessage: String,
    label: AiFeedbackLabel,
    note: String,
    appVersion: String,
    now: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): AiFeedbackEntity = AiFeedbackEntity(
    stage = ResponseSnapshot.stageOf(message),
    label = label.id,
    note = note,
    userMessage = userMessage,
    assistantMessage = message.text,
    assistantDetail = ResponseSnapshot.detailOf(message, zoneId),
    appVersion = appVersion,
    createdAt = now,
)
