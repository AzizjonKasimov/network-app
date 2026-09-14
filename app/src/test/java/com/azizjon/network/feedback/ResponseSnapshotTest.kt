package com.azizjon.network.feedback

import com.azizjon.network.ai.ActionState
import com.azizjon.network.ai.ChatAttachment
import com.azizjon.network.ai.ChatMessage
import com.azizjon.network.ai.ChatRole
import com.azizjon.network.ai.PendingAction
import com.azizjon.network.ai.PendingItem
import com.azizjon.network.data.AiFeedbackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseSnapshotTest {
    @Test
    fun anAgentReplyIsFlattenedIntoSomethingReadableWithoutTheApp() {
        val message = assistantMessage(
            text = "Saved the new job for Synthetic Person.",
            attachment = result(
                saved = listOf("Saved a note on Synthetic Person", "Added a position for Synthetic Person: CTO at Northwind Labs"),
                calls = listOf("find_people {\"query\":\"Synthetic\"}", "add_note {\"person_id\":7}"),
            ),
        )

        val detail = ResponseSnapshot.detailOf(message)

        assertEquals(AiFeedbackEntity.Stage.AGENT, ResponseSnapshot.stageOf(message))
        assertTrue(detail.contains("Saved:"))
        assertTrue(detail.contains("CTO at Northwind Labs"))
        // How the reply got there is the part a saved line cannot show.
        assertTrue(detail.substringAfter("Tool calls, in order:").contains("find_people"))
        assertFalse(detail.contains("Undone"))
    }

    @Test
    fun undoAndAnsweredConfirmationsAreRecorded() {
        val message = assistantMessage(
            text = "Deleting is waiting for you.",
            attachment = result(
                saved = listOf("Archived Synthetic Person"),
                pending = listOf(
                    PendingItem(PendingAction.DeleteNote("action_1", 3, "Synthetic Person", "2026-09-01", "Met at the fair"), ActionState.KEPT),
                    PendingItem(PendingAction.MergePeople("action_2", 1, "Ana Lee", 2, "Ana L."), ActionState.FAILED, "That person no longer exists"),
                ),
            ).copy(undone = true),
        )

        val detail = ResponseSnapshot.detailOf(message)

        assertTrue(detail.contains("Undone by the user afterwards."))
        assertTrue(detail.contains("Met at the fair” [declined]"))
        assertTrue(detail.contains("[failed: That person no longer exists]"))
    }

    @Test
    fun aFailedTurnIsRecordedAsAnError() {
        val message = assistantMessage(text = "The gateway timed out.", attachment = null, failed = true)

        assertEquals(AiFeedbackEntity.Stage.ERROR, ResponseSnapshot.stageOf(message))
        assertEquals("", ResponseSnapshot.detailOf(message))
    }

    @Test
    fun theStoredRowCarriesBothHalvesOfTheExchange() {
        val message = assistantMessage(text = "Ana works at Northwind Labs.", attachment = null)

        val feedback = buildFeedback(
            message = message,
            userMessage = "Where does Ana work?",
            label = AiFeedbackLabel.WRONG_RECORD_TYPE,
            note = "That was a job, not a need",
            appVersion = "0.14.0 (16)",
            now = 1_788_912_000_000L,
        )

        assertEquals(AiFeedbackLabel.WRONG_RECORD_TYPE.id, feedback.label)
        assertEquals("Where does Ana work?", feedback.userMessage)
        assertEquals("Ana works at Northwind Labs.", feedback.assistantMessage)
        assertEquals(AiFeedbackEntity.Stage.MESSAGE, feedback.stage)
        assertEquals("0.14.0 (16)", feedback.appVersion)
    }

    private fun result(
        saved: List<String> = emptyList(),
        pending: List<PendingItem> = emptyList(),
        calls: List<String> = emptyList(),
    ) = ChatAttachment.AgentResult(
        saved = saved,
        changes = emptyList(),
        memory = emptyList(),
        pending = pending,
        people = listOf(7),
        calls = calls,
    )

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
}
