package com.azizjon.network.feedback

import com.azizjon.network.ai.ChatAttachment
import com.azizjon.network.ai.ChatMessage
import com.azizjon.network.ai.ChatRole
import com.azizjon.network.data.AiFeedbackEntity
import com.azizjon.network.data.AiRecordAdd
import com.azizjon.network.data.AiWriteProposal
import com.azizjon.network.data.ProfileField
import com.azizjon.network.data.ProfilePatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ResponseSnapshotTest {
    private val zone: ZoneId = ZoneId.of("UTC")

    @Test
    fun aProposalIsFlattenedIntoSomethingReadableWithoutTheApp() {
        val message = assistantMessage(
            text = "I prepared two changes.",
            attachment = ChatAttachment.Proposal(
                proposal = proposal(
                    newNeeds = listOf(AiRecordAdd("Looking for a co-founder")),
                    newCapabilities = listOf(AiRecordAdd("Runs a hardware lab", selected = false)),
                ),
                caveat = "Two roles were recorded separately.",
            ),
        )

        val detail = ResponseSnapshot.detailOf(message, zone)

        assertEquals(AiFeedbackEntity.Stage.PROPOSAL, ResponseSnapshot.stageOf(message))
        assertTrue(detail.contains("Proposed changes for: Synthetic Person"))
        assertTrue(detail.contains("Interaction date: 2026-09-09"))
        assertTrue(detail.contains("Looking for a co-founder"))
        assertTrue(detail.contains("Two roles were recorded separately."))
        // What the user refused is part of the fault, so it has to be visible.
        assertTrue(detail.contains("Runs a hardware lab [unticked]"))
        assertTrue(detail.contains("Original message stored verbatim"))
    }

    @Test
    fun aContactValueNeverReachesAReport() {
        val message = assistantMessage(
            text = "I prepared a change.",
            attachment = ChatAttachment.Proposal(
                proposal = proposal(
                    patches = listOf(ProfilePatch(ProfileField.CONTACT, "someone@example.test")),
                ),
            ),
        )

        val detail = ResponseSnapshot.detailOf(message, zone)

        assertFalse(detail.contains("someone@example.test"))
        assertTrue(detail.contains(ResponseSnapshot.CONTACT_PLACEHOLDER))
    }

    @Test
    fun aFailedTurnIsRecordedAsAnError() {
        val message = assistantMessage(text = "The gateway timed out.", attachment = null, failed = true)

        assertEquals(AiFeedbackEntity.Stage.ERROR, ResponseSnapshot.stageOf(message))
        assertEquals("", ResponseSnapshot.detailOf(message, zone))
    }

    @Test
    fun theStoredRowCarriesBothHalvesOfTheExchange() {
        val message = assistantMessage(text = "I prepared a change.", attachment = null)

        val feedback = buildFeedback(
            message = message,
            userMessage = "Met a synthetic person today",
            label = AiFeedbackLabel.WRONG_RECORD_TYPE,
            note = "That was a job, not a need",
            appVersion = "0.9.0 (10)",
            now = 1_788_912_000_000L,
            zoneId = zone,
        )

        assertEquals(AiFeedbackLabel.WRONG_RECORD_TYPE.id, feedback.label)
        assertEquals("Met a synthetic person today", feedback.userMessage)
        assertEquals("I prepared a change.", feedback.assistantMessage)
        assertEquals(AiFeedbackEntity.Stage.MESSAGE, feedback.stage)
        assertEquals("0.9.0 (10)", feedback.appVersion)
    }

    private fun assistantMessage(
        text: String,
        attachment: ChatAttachment?,
        failed: Boolean = false,
    ) = ChatMessage(
        id = 2,
        role = ChatRole.ASSISTANT,
        text = text,
        attachment = attachment,
        failed = failed,
        sentAt = 1_788_912_000_000L,
        fromGateway = true,
    )

    private fun proposal(
        patches: List<ProfilePatch> = emptyList(),
        newNeeds: List<AiRecordAdd> = emptyList(),
        newCapabilities: List<AiRecordAdd> = emptyList(),
    ) = AiWriteProposal(
        rawInput = "Met a synthetic person today",
        targetPersonId = 7,
        targetName = "Synthetic Person",
        occurredAt = 1_788_912_000_000L,
        profilePatches = patches,
        newNeeds = newNeeds,
        newCapabilities = newCapabilities,
    )
}
