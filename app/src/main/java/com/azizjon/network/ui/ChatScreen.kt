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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azizjon.network.ai.AgentClient
import com.azizjon.network.ai.ChatAttachment
import com.azizjon.network.ai.ChatMessage
import com.azizjon.network.ai.ChatRole
import com.azizjon.network.ai.ChatState
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.feedback.AiFeedbackLabel

/**
 * The single conversational surface for recording, correcting, and asking.
 *
 * Every message goes to the assistant, which decides what to look up and what
 * to change. The thread shows what each reply saved, with an undo, and holds
 * deletes and merges on a card until the user confirms them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chat: ChatState,
    snapshot: NetworkSnapshot,
    draft: String,
    consentRequested: Boolean,
    feedbackTarget: ChatMessage?,
    speechFallbackAllowed: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAllowSpeechFallback: () -> Unit,
    onOpenPerson: (Long) -> Unit,
    onUndo: (Long) -> Unit,
    onConfirmAction: (Long, String) -> Unit,
    onKeepAction: (Long, String) -> Unit,
    onConfirmConsent: () -> Unit,
    onDismissConsent: () -> Unit,
    onReportMessage: (Long) -> Unit,
    onDismissFeedback: () -> Unit,
    onSubmitFeedback: (AiFeedbackLabel, String) -> Unit,
    onNewChat: () -> Unit,
) {
    val listState = rememberLazyListState()
    val busy = chat.phase.busy

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
                            onUndo = { onUndo(message.id) },
                            onConfirmAction = { actionId -> onConfirmAction(message.id, actionId) },
                            onKeepAction = { actionId -> onKeepAction(message.id, actionId) },
                            onReport = onReportMessage,
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
            speechFallbackAllowed = speechFallbackAllowed,
            onDraftChange = onDraftChange,
            onAllowSpeechFallback = onAllowSpeechFallback,
            onSend = onSend,
        )
    }

    if (consentRequested) {
        AssistantConsentDialog(onConfirm = onConfirmConsent, onDismiss = onDismissConsent)
    }
    if (feedbackTarget != null) {
        FeedbackDialog(
            responsePreview = feedbackTarget.text,
            onDismiss = onDismissFeedback,
            onSubmit = onSubmitFeedback,
        )
    }
}

@Composable
private fun ChatMessageRow(
    message: ChatMessage,
    snapshot: NetworkSnapshot,
    busy: Boolean,
    onOpenPerson: (Long) -> Unit,
    onUndo: () -> Unit,
    onConfirmAction: (String) -> Unit,
    onKeepAction: (String) -> Unit,
    onReport: (Long) -> Unit,
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
            is ChatAttachment.AgentResult -> AgentResultCard(
                result = attachment,
                snapshot = snapshot,
                busy = busy,
                onUndo = onUndo,
                onConfirm = onConfirmAction,
                onKeep = onKeepAction,
                onOpenPerson = onOpenPerson,
            )
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
 * It has to sit here rather than in Settings: once the thread is cleared there
 * is nothing left to point at.
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
    speechFallbackAllowed: Boolean,
    onDraftChange: (String) -> Unit,
    onAllowSpeechFallback: () -> Unit,
    onSend: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= AgentClient.MAX_INPUT_CHARACTERS) onDraftChange(it) },
                enabled = !busy,
                placeholder = { Text("Tell me about someone, or ask anything") },
                maxLines = 6,
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(onClick = onSend, enabled = draft.isNotBlank() && !busy) {
                Text("↑")
            }
        }
        VoiceInputControl(
            value = draft,
            maxCharacters = AgentClient.MAX_INPUT_CHARACTERS,
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
            Text("Tell me about someone you spoke with, and I will save it on the right people.")
            Text("Ask anything about your network: who could help with a goal, what you last discussed with someone, who works where.")
            Text(
                "Changes save straight away and can be undone from my reply. Deleting and merging always wait for you to confirm.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AssistantConsentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Let the assistant read your network?") },
        text = {
            Text(
                "To answer questions and file things on the right people, the assistant reads whichever records it needs " +
                    "and sends them through your private AI gateway to Anthropic: names, positions, education, locations, " +
                    "relationship context, tags, profile notes, notes, needs, capabilities, background facts, and dates. " +
                    "Contact values stay on this phone. What it saves can be undone from its reply, and deleting or merging " +
                    "always waits for you. You can revoke this in Settings.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
