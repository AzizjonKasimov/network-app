package com.azizjon.network.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azizjon.network.ai.ChatAttachment
import com.azizjon.network.ai.ChatMessage
import com.azizjon.network.ai.ChatRole
import com.azizjon.network.ai.ChatState
import com.azizjon.network.ai.GatewayClient
import com.azizjon.network.data.AiWriteProposal
import com.azizjon.network.data.MoveDestination
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.feedback.AiFeedbackLabel

/**
 * The single conversational surface for capture, correction, and search.
 *
 * Capture and search used to be separate screens, so the screen itself said
 * which one a message meant. Here the assistant routes each turn instead, and
 * an open proposal turns the composer into a refinement box so a follow-up
 * correction lands on the pending change rather than starting a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chat: ChatState,
    snapshot: NetworkSnapshot,
    draft: String,
    searchConsentRequest: String?,
    feedbackTarget: ChatMessage?,
    speechFallbackAllowed: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAllowSpeechFallback: () -> Unit,
    onOpenPerson: (Long) -> Unit,
    onChooseTarget: (Long?) -> Unit,
    onChangeProposalTarget: () -> Unit,
    onUpdateProposal: (AiWriteProposal) -> Unit,
    onDiscardProposal: () -> Unit,
    onApplyProposal: () -> Unit,
    onConfirmSearchConsent: () -> Unit,
    onDismissSearchConsent: () -> Unit,
    onReportMessage: (Long) -> Unit,
    onMoveInteraction: (Long, MoveDestination) -> Unit,
    onDismissFeedback: () -> Unit,
    onSubmitFeedback: (AiFeedbackLabel, String) -> Unit,
    onNewChat: () -> Unit,
) {
    val listState = rememberLazyListState()
    val busy = chat.phase.busy
    var moveRequest by remember { mutableStateOf<MoveRequest?>(null) }
    val pendingName = chat.pendingProposal?.second?.targetName

    LaunchedEffect(chat.messages.size, chat.phase) {
        if (chat.messages.isNotEmpty()) listState.animateScrollToItem(chat.messages.lastIndex)
    }

    // imePadding on the column keeps the composer above the keyboard; without it
    // the thread outgrows the resized window and rides up under the status bar.
    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text("Assistant") },
            actions = {
                if (chat.messages.isNotEmpty()) {
                    TextButton(onClick = onNewChat, enabled = !busy) { Text("New") }
                }
            },
        )
        Box(Modifier.weight(1f)) {
            if (chat.messages.isEmpty()) {
                EmptyChatState(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(chat.messages, key = { it.id }) { message ->
                        ChatMessageRow(
                            message = message,
                            snapshot = snapshot,
                            busy = busy,
                            onOpenPerson = onOpenPerson,
                            onChooseTarget = onChooseTarget,
                            onChangeProposalTarget = onChangeProposalTarget,
                            onUpdateProposal = onUpdateProposal,
                            onDiscardProposal = onDiscardProposal,
                            onApplyProposal = onApplyProposal,
                            onReport = onReportMessage,
                            onMoveNote = { saved ->
                                saved.savedInteractionId?.let { id ->
                                    moveRequest = MoveRequest(
                                        interactionId = id,
                                        note = saved.proposal.rawInput,
                                        fromName = saved.savedPersonId?.let(snapshot::person)?.name
                                            ?: saved.proposal.targetName,
                                    )
                                }
                            },
                        )
                    }
                    if (busy) {
                        item("phase") { PhaseRow(chat.phase.label) }
                    }
                }
            }
        }
        HorizontalDivider()
        Composer(
            draft = draft,
            busy = busy,
            refiningName = pendingName,
            speechFallbackAllowed = speechFallbackAllowed,
            onDraftChange = onDraftChange,
            onAllowSpeechFallback = onAllowSpeechFallback,
            onSend = onSend,
        )
    }

    if (searchConsentRequest != null) {
        SearchConsentDialog(onConfirm = onConfirmSearchConsent, onDismiss = onDismissSearchConsent)
    }
    if (feedbackTarget != null) {
        FeedbackDialog(
            responsePreview = feedbackTarget.text,
            onDismiss = onDismissFeedback,
            onSubmit = onSubmitFeedback,
        )
    }
    moveRequest?.let { request ->
        MoveNoteDialog(
            note = request.note,
            fromName = request.fromName,
            snapshot = snapshot,
            onDismiss = { moveRequest = null },
            onMove = { destination ->
                onMoveInteraction(request.interactionId, destination)
                moveRequest = null
            },
        )
    }
}

/** An applied capture the user wants re-filed, held while the dialog is open. */
private data class MoveRequest(val interactionId: Long, val note: String, val fromName: String)

@Composable
private fun ChatMessageRow(
    message: ChatMessage,
    snapshot: NetworkSnapshot,
    busy: Boolean,
    onOpenPerson: (Long) -> Unit,
    onChooseTarget: (Long?) -> Unit,
    onChangeProposalTarget: () -> Unit,
    onUpdateProposal: (AiWriteProposal) -> Unit,
    onDiscardProposal: () -> Unit,
    onApplyProposal: () -> Unit,
    onReport: (Long) -> Unit,
    onMoveNote: (ChatAttachment.Proposal) -> Unit,
) {
    val fromUser = message.role == ChatRole.USER
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (message.text.isNotBlank()) {
            Surface(
                color = when {
                    message.failed -> MaterialTheme.colorScheme.errorContainer
                    fromUser -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = when {
                    message.failed -> MaterialTheme.colorScheme.onErrorContainer
                    fromUser -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(if (fromUser) 0.9f else 1f),
            ) {
                Text(message.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        when (val attachment = message.attachment) {
            is ChatAttachment.Proposal -> ProposalCard(
                proposal = attachment.proposal,
                person = attachment.proposal.targetPersonId?.let(snapshot::person),
                snapshot = snapshot,
                applied = attachment.applied,
                savedPersonId = attachment.savedPersonId,
                savedInteractionId = attachment.savedInteractionId,
                caveat = attachment.caveat,
                busy = busy,
                onChangeTarget = onChangeProposalTarget,
                onMoveNote = { onMoveNote(attachment) },
                onUpdate = onUpdateProposal,
                onDiscard = onDiscardProposal,
                onApply = onApplyProposal,
                onOpenPerson = onOpenPerson,
            )
            is ChatAttachment.TargetChoice -> TargetChoiceCard(attachment.value, snapshot, busy, onChooseTarget)
            is ChatAttachment.Search -> SearchResultsCard(attachment.results, onOpenPerson)
            null -> Unit
        }
        if (message.fromGateway) {
            FeedbackRow(reportedLabel = message.reportedLabel, enabled = !busy) { onReport(message.id) }
        }
    }
}

/**
 * The one place a bad answer can be labelled, right under the answer itself.
 *
 * It has to sit here rather than in Settings: the proposal card above it is
 * about to be applied or discarded, and once it is gone there is nothing left
 * to point at.
 */
@Composable
private fun FeedbackRow(reportedLabel: String?, enabled: Boolean, onReport: () -> Unit) {
    if (reportedLabel != null) {
        Text(
            "Reported: " + AiFeedbackLabel.titleFor(reportedLabel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        TextButton(onClick = onReport, enabled = enabled) {
            Text("Report a problem", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PhaseRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Composer(
    draft: String,
    busy: Boolean,
    refiningName: String?,
    speechFallbackAllowed: Boolean,
    onDraftChange: (String) -> Unit,
    onAllowSpeechFallback: () -> Unit,
    onSend: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (refiningName != null) {
            Text(
                "Refining the proposed changes for $refiningName",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= GatewayClient.MAX_INPUT_CHARACTERS) onDraftChange(it) },
                enabled = !busy,
                placeholder = {
                    Text(if (refiningName == null) "Tell me about someone, or ask who could help" else "What should I change?")
                },
                maxLines = 6,
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(onClick = onSend, enabled = draft.isNotBlank() && !busy) {
                Text("↑")
            }
        }
        VoiceInputControl(
            value = draft,
            maxCharacters = GatewayClient.MAX_INPUT_CHARACTERS,
            enabled = !busy,
            fallbackAllowed = speechFallbackAllowed,
            onAllowFallback = onAllowSpeechFallback,
            onValueChange = onDraftChange,
        )
    }
}

@Composable
private fun EmptyChatState(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Start a conversation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Write a note about someone you spoke with, and I will prepare an editable change for you to approve.")
            Text("Ask who in your network could help with a goal or problem, and I will rank people with the stored evidence behind each one.")
            Text(
                "Nothing is saved until you apply it, and you can correct a proposal by replying to it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SearchConsentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send active network text to the AI gateway?") },
        text = {
            Text(
                "Searching your whole network sends names, roles, organizations, locations, relationship context, tags, notes, interactions, active needs, active capabilities, and dates to your private AI gateway. Contact values, archived people, closed needs, and inactive capabilities are excluded. This choice is remembered and can be revoked in Settings.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Accept and search") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
