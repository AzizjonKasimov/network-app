package com.azizjon.network.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azizjon.network.data.NetworkRepository
import com.azizjon.network.feedback.AiFeedbackLabel

/**
 * Asks what was wrong with one assistant response.
 *
 * A label is required and the note is not, because the label is the part that
 * can be counted across reports while the note is the part that explains the
 * one in front of you. The dialog says plainly that nothing is sent anywhere:
 * a report is a local row until the user exports it themselves.
 */
@Composable
fun FeedbackDialog(
    responsePreview: String,
    onDismiss: () -> Unit,
    onSubmit: (AiFeedbackLabel, String) -> Unit,
) {
    var selected by remember { mutableStateOf<AiFeedbackLabel?>(null) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What went wrong?") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (responsePreview.isNotBlank()) {
                    Text(
                        responsePreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AiFeedbackLabel.entries.forEach { label ->
                    LabelRow(label, label == selected) { selected = label }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = {
                        if (it.length <= NetworkRepository.MAX_FEEDBACK_NOTE_CHARACTERS) note = it
                    },
                    label = { Text("What should it have done? (optional)") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The report is stored on this phone with a copy of the message and the response. Nothing leaves the phone until you export it from Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { selected?.let { onSubmit(it, note) } },
            ) { Text("Save report") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LabelRow(label: AiFeedbackLabel, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(top = 12.dp)) {
            Text(label.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(
                label.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
