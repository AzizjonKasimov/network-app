package com.azizjon.network.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azizjon.network.ai.AiCaptureState
import com.azizjon.network.data.AiCapabilityEdit
import com.azizjon.network.data.AiInteractionEdit
import com.azizjon.network.data.AiNeedEdit
import com.azizjon.network.data.AiRecordAdd
import com.azizjon.network.data.AiWriteProposal
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity
import com.azizjon.network.data.ProfileField
import com.azizjon.network.data.ProfilePatch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun AiCaptureCard(
    draft: String,
    state: AiCaptureState,
    onDraftChange: (String) -> Unit,
    speechFallbackAllowed: Boolean,
    onAllowSpeechFallback: () -> Unit,
    onInterpret: () -> Unit,
) {
    val busy = state == AiCaptureState.Resolving || state == AiCaptureState.BuildingProposal || state == AiCaptureState.Applying
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Capture or update with AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Write naturally about one person. The assistant prepares an editable proposal; nothing is saved until you apply it.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= 4_000) onDraftChange(it) },
                enabled = !busy,
                label = { Text("Write a note or update") },
                minLines = 3,
                supportingText = { Text("${draft.length}/4000 · The applied text is kept verbatim as an AI-reviewed interaction") },
                modifier = Modifier.fillMaxWidth(),
            )
            VoiceInputControl(
                value = draft,
                maxCharacters = 4_000,
                enabled = !busy,
                fallbackAllowed = speechFallbackAllowed,
                onAllowFallback = onAllowSpeechFallback,
                onValueChange = onDraftChange,
            )
            Button(
                enabled = draft.isNotBlank() && !busy,
                onClick = onInterpret,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when (state) {
                        AiCaptureState.Resolving -> "Identifying person…"
                        AiCaptureState.BuildingProposal -> "Building proposal…"
                        AiCaptureState.Applying -> "Applying reviewed changes…"
                        else -> "Interpret"
                    },
                )
            }
            if (state is AiCaptureState.Error) {
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun AiCaptureDialogs(
    state: AiCaptureState,
    snapshot: NetworkSnapshot,
    onChooseTarget: (Long?) -> Unit,
    onChangeTarget: () -> Unit,
    onUpdateProposal: (AiWriteProposal) -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    when (state) {
        is AiCaptureState.ChooseTarget -> TargetChoiceDialog(state.value.targetName, state.value.suggestions, onChooseTarget, onCancel)
        is AiCaptureState.Preview -> ProposalReviewDialog(
            proposal = state.proposal,
            person = state.proposal.targetPersonId?.let(snapshot::person),
            snapshot = snapshot,
            onChangeTarget = onChangeTarget,
            onUpdate = onUpdateProposal,
            onCancel = onCancel,
            onApply = onApply,
        )
        else -> Unit
    }
}

@Composable
private fun TargetChoiceDialog(
    targetName: String,
    suggestions: List<PersonEntity>,
    onChoose: (Long?) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Who is this about?") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("The assistant identified “$targetName”. Choose an existing person or create a new profile.")
                suggestions.forEach { person ->
                    TextButton(onClick = { onChoose(person.id) }, modifier = Modifier.fillMaxWidth()) {
                        val detail = listOf(person.role, person.organization).filter(String::isNotBlank).joinToString(" · ")
                        Text(if (detail.isBlank()) person.name else "${person.name} — $detail")
                    }
                }
                TextButton(onClick = { onChoose(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Create new person: $targetName")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProposalReviewDialog(
    proposal: AiWriteProposal,
    person: PersonEntity?,
    snapshot: NetworkSnapshot,
    onChangeTarget: () -> Unit,
    onUpdate: (AiWriteProposal) -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Review AI proposal") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (person == null) "New person: ${proposal.targetName}" else "Update: ${person.name}",
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onChangeTarget) { Text("Change target") }
                Text("The original note will always be stored verbatim as an AI-reviewed interaction.", style = MaterialTheme.typography.bodySmall)
                if (proposal.interactionOnlyFacts.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Kept only in the original interaction", fontWeight = FontWeight.SemiBold)
                            Text(
                                "These explicit facts were not mapped to a profile field, need, capability, or supported edit. They will still be saved verbatim with the original note.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            proposal.interactionOnlyFacts.forEach { fact -> Text("• $fact") }
                        }
                    }
                }
                DateField("Interaction date", proposal.occurredAt) { onUpdate(proposal.copy(occurredAt = it)) }

                ProposalHeading("Profile changes", proposal.profilePatches.size)
                proposal.profilePatches.forEachIndexed { index, patch ->
                    val before = person?.profileValue(patch.field).orEmpty()
                    SelectableTextEdit(
                        selected = patch.selected,
                        title = patch.field.displayName(),
                        before = before,
                        value = patch.value,
                        onSelectedChange = { selected ->
                            onUpdate(proposal.copy(profilePatches = proposal.profilePatches.replace(index, patch.copy(selected = selected))))
                        },
                        onValueChange = { value ->
                            onUpdate(proposal.copy(profilePatches = proposal.profilePatches.replace(index, patch.copy(value = value))))
                        },
                    )
                }

                ProposalHeading("New needs", proposal.newNeeds.size)
                proposal.newNeeds.forEachIndexed { index, item ->
                    SelectableTextEdit(item.selected, "New need", "", item.text, { selected ->
                        onUpdate(proposal.copy(newNeeds = proposal.newNeeds.replace(index, item.copy(selected = selected))))
                    }, { value ->
                        onUpdate(proposal.copy(newNeeds = proposal.newNeeds.replace(index, item.copy(text = value))))
                    })
                }

                ProposalHeading("New capabilities", proposal.newCapabilities.size)
                proposal.newCapabilities.forEachIndexed { index, item ->
                    SelectableTextEdit(item.selected, "New capability", "", item.text, { selected ->
                        onUpdate(proposal.copy(newCapabilities = proposal.newCapabilities.replace(index, item.copy(selected = selected))))
                    }, { value ->
                        onUpdate(proposal.copy(newCapabilities = proposal.newCapabilities.replace(index, item.copy(text = value))))
                    })
                }

                ProposalHeading("Interaction edits", proposal.interactionEdits.size)
                proposal.interactionEdits.forEachIndexed { index, edit ->
                    val before = snapshot.interactionsFor(proposal.targetPersonId ?: -1).firstOrNull { it.id == edit.id }?.note.orEmpty()
                    InteractionEditCard(edit, before, { changed ->
                        onUpdate(proposal.copy(interactionEdits = proposal.interactionEdits.replace(index, changed)))
                    })
                }

                ProposalHeading("Need edits", proposal.needEdits.size)
                proposal.needEdits.forEachIndexed { index, edit ->
                    val before = snapshot.needsFor(proposal.targetPersonId ?: -1).firstOrNull { it.id == edit.id }?.text.orEmpty()
                    NeedEditCard(edit, before, { changed ->
                        onUpdate(proposal.copy(needEdits = proposal.needEdits.replace(index, changed)))
                    })
                }

                ProposalHeading("Capability edits", proposal.capabilityEdits.size)
                proposal.capabilityEdits.forEachIndexed { index, edit ->
                    val before = snapshot.capabilitiesFor(proposal.targetPersonId ?: -1).firstOrNull { it.id == edit.id }?.text.orEmpty()
                    CapabilityEditCard(edit, before, { changed ->
                        onUpdate(proposal.copy(capabilityEdits = proposal.capabilityEdits.replace(index, changed)))
                    })
                }
            }
        },
        confirmButton = { TextButton(onClick = onApply) { Text("Apply reviewed changes") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun ProposalHeading(title: String, count: Int) {
    if (count > 0) Text("$title ($count)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SelectableTextEdit(
    selected: Boolean,
    title: String,
    before: String,
    value: String,
    onSelectedChange: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(selected, onSelectedChange)
                Text(title, fontWeight = FontWeight.SemiBold)
            }
            if (before.isNotBlank() && before != value) Text("Before: $before", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value, onValueChange, enabled = selected, modifier = Modifier.fillMaxWidth(), minLines = 1)
        }
    }
}

@Composable
private fun InteractionEditCard(edit: AiInteractionEdit, before: String, onChange: (AiInteractionEdit) -> Unit) {
    SelectableTextEdit(edit.selected, "Edit interaction #${edit.id}", before, edit.note, { onChange(edit.copy(selected = it)) }, { onChange(edit.copy(note = it)) })
    if (edit.selected) DateField("Interaction date", edit.occurredAt) { onChange(edit.copy(occurredAt = it)) }
}

@Composable
private fun NeedEditCard(edit: AiNeedEdit, before: String, onChange: (AiNeedEdit) -> Unit) {
    SelectableTextEdit(edit.selected, "Edit need #${edit.id}", before, edit.text, { onChange(edit.copy(selected = it)) }, { onChange(edit.copy(text = it)) })
    if (edit.selected) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(edit.status == NeedEntity.STATUS_ACTIVE, { active ->
                onChange(edit.copy(status = if (active) NeedEntity.STATUS_ACTIVE else NeedEntity.STATUS_CLOSED))
            })
            Text(if (edit.status == NeedEntity.STATUS_ACTIVE) "Active" else "Closed", modifier = Modifier.padding(start = 8.dp))
        }
        DateField("Last confirmed", edit.lastConfirmedAt) { onChange(edit.copy(lastConfirmedAt = it)) }
    }
}

@Composable
private fun CapabilityEditCard(edit: AiCapabilityEdit, before: String, onChange: (AiCapabilityEdit) -> Unit) {
    SelectableTextEdit(edit.selected, "Edit capability #${edit.id}", before, edit.text, { onChange(edit.copy(selected = it)) }, { onChange(edit.copy(text = it)) })
    if (edit.selected) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(edit.active, { onChange(edit.copy(active = it)) })
            Text(if (edit.active) "Active" else "Inactive", modifier = Modifier.padding(start = 8.dp))
        }
        DateField("Last confirmed", edit.lastConfirmedAt) { onChange(edit.copy(lastConfirmedAt = it)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, timestamp: Long, onChange: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("$label: ${formatAiDate(timestamp)}") }
    if (open) {
        val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = timestamp)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utcMillis ->
                        val localDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    }
                    open = false
                }) { Text("Use date") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

private fun ProfileField.displayName(): String = name.lowercase().replaceFirstChar { it.uppercase() }

private fun PersonEntity.profileValue(field: ProfileField): String = when (field) {
    ProfileField.NAME -> name
    ProfileField.ORGANIZATION -> organization
    ProfileField.ROLE -> role
    ProfileField.LOCATION -> location
    ProfileField.CONTACT -> contact
    ProfileField.RELATIONSHIP -> relationship
    ProfileField.TAGS -> tags
    ProfileField.NOTES -> notes
}

private fun <T> List<T>.replace(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }

private val aiDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private fun formatAiDate(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().format(aiDateFormatter)
