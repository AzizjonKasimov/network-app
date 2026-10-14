package com.azizjon.network.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azizjon.network.checkin.NewPersonItem
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity

/**
 * The check-in's "Already saved": the user says who a contact or chat name is.
 *
 * Saved people whose names come close are listed first, and anyone else is a
 * search away, because a name in another script or a nickname shares no words
 * with the saved one. Picking someone saves the name among their other names.
 */
@Composable
fun LinkPersonDialog(
    item: NewPersonItem,
    snapshot: NetworkSnapshot,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val typed = query.trim()
    val identity = remember(snapshot) { snapshot.identityLines() }
    val likely = item.maybe.map { it.id }.toSet()
    val candidates = remember(snapshot.people, item, typed) {
        val pool = snapshot.people.filterNot { it.archived }.sortedBy { it.name.lowercase() }
        if (typed.isEmpty()) {
            pool.sortedByDescending { it.id in likely }.take(MAX_SHOWN)
        } else {
            pool.filter { person -> (listOf(person.name) + person.otherNames).any { it.contains(typed, ignoreCase = true) } }.take(MAX_SHOWN)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who is ${item.name}?") },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Pick them, and \"${item.name}\" is saved as another name for them, so the check-in knows them by it from now on.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(MAX_QUERY_CHARACTERS) },
                    label = { Text("Search your network") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                candidates.forEach { person ->
                    PersonChoice(
                        person = person,
                        detail = listOf(if (typed.isEmpty() && person.id in likely) "Close match" else "", identity[person.id].orEmpty())
                            .filter(String::isNotEmpty)
                            .joinToString(" · "),
                        onClick = { onPick(person.id) },
                    )
                }
                if (candidates.isEmpty()) {
                    Text(
                        if (typed.isEmpty()) "Nobody is saved yet." else "Nobody saved matches that.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PersonChoice(person: PersonEntity, detail: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Choose ${person.name}", onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(
            if (person.isSelf) "${person.name} (you)" else person.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (detail.isNotEmpty()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val MAX_SHOWN = 8
private const val MAX_QUERY_CHARACTERS = 200
