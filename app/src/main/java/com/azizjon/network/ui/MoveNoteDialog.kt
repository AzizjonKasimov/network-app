package com.azizjon.network.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azizjon.network.data.MoveDestination
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity

/**
 * Sends a misfiled note, and everything it created, to the right person.
 *
 * One field does both jobs: it filters the people already saved and, when
 * nothing matches, becomes the name of a person to create. That is the shape of
 * the actual mistake - a note about somebody new filed under whoever was
 * mentioned beside them - so creating the missing person has to be as close to
 * hand as picking an existing one.
 */
@Composable
fun MoveNoteDialog(
    note: String,
    fromName: String,
    snapshot: NetworkSnapshot,
    onDismiss: () -> Unit,
    onMove: (MoveDestination) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val typed = query.trim()
    val candidates = remember(snapshot.people, fromName, typed) {
        snapshot.people
            .filterNot { it.archived || it.name.equals(fromName, ignoreCase = true) }
            .filter { typed.isEmpty() || it.name.contains(typed, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .take(MAX_SUGGESTIONS)
    }
    val exactMatch = snapshot.people.any { it.name.equals(typed, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move this note") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "The note and every position, need, capability, and background record it created move together.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Profile details it changed on $fromName stay there, because a changed field keeps no record of where it came from. Check them by hand afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { if (it.length <= MAX_NAME_CHARACTERS) query = it },
                    label = { Text("Search, or type a new name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                candidates.forEach { person ->
                    TextButton(
                        onClick = { onMove(MoveDestination.Existing(person.id)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label(person, snapshot), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (candidates.isEmpty() && typed.isNotEmpty() && exactMatch) {
                    Text(
                        "That is the person this note is already on.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (candidates.isEmpty() && typed.isEmpty()) {
                    Text("No other people saved yet.", style = MaterialTheme.typography.bodySmall)
                }
                if (typed.isNotEmpty() && !exactMatch) {
                    TextButton(
                        onClick = { onMove(MoveDestination.NewPerson(typed)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Create new person: $typed")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun label(person: PersonEntity, snapshot: NetworkSnapshot): String {
    val detail = snapshot.affiliationSummary(person.id)
    return if (detail.isBlank()) person.name else "${person.name} — $detail"
}

private const val MAX_SUGGESTIONS = 8
private const val MAX_NAME_CHARACTERS = 200
