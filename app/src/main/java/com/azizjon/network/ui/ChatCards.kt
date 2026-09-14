package com.azizjon.network.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azizjon.network.ai.ActionState
import com.azizjon.network.ai.ChatAttachment
import com.azizjon.network.ai.PendingAction
import com.azizjon.network.ai.PendingItem
import com.azizjon.network.data.NetworkSnapshot

/**
 * What one assistant reply did, under the reply.
 *
 * Saved changes are listed with a single Undo for all of them, because a reply
 * that filed a note on the wrong person usually got several things wrong at
 * once. Deletes and merges are listed separately and wait for a tap: they are
 * the changes Undo could not take back, so they never happen on their own.
 */
@Composable
fun AgentResultCard(
    result: ChatAttachment.AgentResult,
    snapshot: NetworkSnapshot,
    busy: Boolean,
    onUndo: () -> Unit,
    onConfirm: (String) -> Unit,
    onKeep: (String) -> Unit,
    onOpenPerson: (Long) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (result.saved.isNotEmpty()) {
                Text(
                    if (result.undone) "Undone" else "Saved",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (result.undone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
                result.saved.forEach { line ->
                    Text(
                        "• $line",
                        style = MaterialTheme.typography.bodySmall,
                        textDecoration = if (result.undone) TextDecoration.LineThrough else TextDecoration.None,
                    )
                }
                when {
                    result.undoError != null -> Text(
                        result.undoError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    result.canUndo -> OutlinedButton(onClick = onUndo, enabled = !busy) { Text("Undo") }
                }
            }

            result.pending.forEach { item ->
                PendingActionCard(item = item, busy = busy, onConfirm = { onConfirm(item.action.id) }, onKeep = { onKeep(item.action.id) })
            }

            // Deleted or merged-away people drop out of the snapshot, and with it
            // out of this list, so a link never leads nowhere.
            val people = result.people.mapNotNull(snapshot::person).take(MAX_PERSON_LINKS)
            if (people.isNotEmpty()) {
                HorizontalDivider()
                people.forEach { person ->
                    TextButton(onClick = { onOpenPerson(person.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open ${person.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingActionCard(item: PendingItem, busy: Boolean, onConfirm: () -> Unit, onKeep: () -> Unit) {
    val merge = item.action is PendingAction.MergePeople
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (merge) "Waiting for you to confirm a merge" else "Waiting for you to confirm a delete",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(item.action.description, style = MaterialTheme.typography.bodyMedium)
            when (item.state) {
                ActionState.PENDING -> {
                    Text("This cannot be undone.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(if (merge) "Merge" else "Delete")
                        }
                        OutlinedButton(onClick = onKeep, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(if (merge) "Keep both" else "Keep")
                        }
                    }
                }
                ActionState.DONE -> Text(item.outcome ?: "Done.", color = MaterialTheme.colorScheme.primary)
                ActionState.KEPT -> Text(if (merge) "Left as two people." else "Kept. Nothing was deleted.")
                ActionState.FAILED -> Text(item.outcome ?: "That could not be done.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private const val MAX_PERSON_LINKS = 4
