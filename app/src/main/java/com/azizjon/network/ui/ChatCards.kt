package com.azizjon.network.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azizjon.network.ai.AiPersonSearchResult
import com.azizjon.network.ai.ChatAttachment
import com.azizjon.network.ai.MoveCandidate
import com.azizjon.network.ai.TargetChoiceState
import com.azizjon.network.data.AiAffiliationAdd
import com.azizjon.network.data.AiAffiliationEdit
import com.azizjon.network.data.AiCapabilityEdit
import com.azizjon.network.data.AiFactEdit
import com.azizjon.network.data.AiInteractionEdit
import com.azizjon.network.data.AiNeedEdit
import com.azizjon.network.data.AiWriteProposal
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity
import com.azizjon.network.data.ProfileField
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The editable proposal, rendered inline in the thread.
 *
 * Nothing is written until Apply. A correction typed into the composer revises
 * this card instead of starting a new capture, so small fixes stay in the
 * conversation rather than forcing a re-edit here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposalCard(
    proposal: AiWriteProposal,
    person: PersonEntity?,
    snapshot: NetworkSnapshot,
    applied: Boolean,
    savedPersonId: Long?,
    savedInteractionId: Long?,
    caveat: String?,
    busy: Boolean,
    onChangeTarget: () -> Unit,
    onMoveNote: () -> Unit,
    onUpdate: (AiWriteProposal) -> Unit,
    onDiscard: () -> Unit,
    onApply: () -> Unit,
    onOpenPerson: (Long) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (person == null) "New person: ${proposal.targetName}" else "Update: ${person.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!applied) TextButton(onClick = onChangeTarget, enabled = !busy) { Text("Change") }
            }
            Text(
                "The message is stored verbatim as an AI-reviewed interaction either way.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (caveat != null) {
                // How an awkward fact was handled. Advisory: the proposal below is
                // complete and applying it is the normal next step.
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("How this was handled", fontWeight = FontWeight.SemiBold)
                        Text(caveat, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
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
                        proposal.interactionOnlyFacts.forEach { fact -> Text("\u2022 $fact") }
                    }
                }
            }
            if (applied) {
                AppliedProposalSummary(proposal)
                savedPersonId?.let { id ->
                    OutlinedButton(onClick = { onOpenPerson(id) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open ${proposal.targetName}")
                    }
                }
                // A wrong target is usually spotted right after saving, so the
                // remedy belongs here rather than only on the person's screen.
                if (savedInteractionId != null) {
                    TextButton(onClick = onMoveNote, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Wrong person? Move this note")
                    }
                }
                return@Column
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

            ProposalHeading("Positions", proposal.newAffiliations.size)
            proposal.newAffiliations.forEachIndexed { index, item ->
                AffiliationAddCard(item) { changed ->
                    onUpdate(proposal.copy(newAffiliations = proposal.newAffiliations.replace(index, changed)))
                }
            }

            ProposalHeading("Position changes", proposal.affiliationEdits.size)
            proposal.affiliationEdits.forEachIndexed { index, edit ->
                val before = snapshot.affiliationsFor(proposal.targetPersonId ?: -1)
                    .firstOrNull { it.id == edit.id }?.label.orEmpty()
                AffiliationEditCard(edit, before) { changed ->
                    onUpdate(proposal.copy(affiliationEdits = proposal.affiliationEdits.replace(index, changed)))
                }
            }

            ProposalHeading("Background", proposal.newFacts.size)
            proposal.newFacts.forEachIndexed { index, item ->
                SelectableTextEdit(item.selected, "New background fact", "", item.text, { selected ->
                    onUpdate(proposal.copy(newFacts = proposal.newFacts.replace(index, item.copy(selected = selected))))
                }, { value ->
                    onUpdate(proposal.copy(newFacts = proposal.newFacts.replace(index, item.copy(text = value))))
                })
            }

            ProposalHeading("Background changes", proposal.factEdits.size)
            proposal.factEdits.forEachIndexed { index, edit ->
                val before = snapshot.factsFor(proposal.targetPersonId ?: -1)
                    .firstOrNull { it.id == edit.id }?.text.orEmpty()
                FactEditCard(edit, before) { changed ->
                    onUpdate(proposal.copy(factEdits = proposal.factEdits.replace(index, changed)))
                }
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

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApply, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("Apply")
                }
                OutlinedButton(onClick = onDiscard, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("Discard")
                }
            }
            Text(
                "Or type a correction below to revise this before saving.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What actually got written, once the proposal is no longer editable. */
@Composable
private fun AppliedProposalSummary(proposal: AiWriteProposal) {
    val lines = buildList {
        proposal.profilePatches.filter { it.selected }.forEach { add("${it.field.displayName()} set") }
        proposal.newAffiliations.filter { it.selected }.forEach {
            add("Position: " + listOf(it.role, it.organization).filter(String::isNotBlank).joinToString(" at "))
        }
        proposal.affiliationEdits.filter { it.selected }.forEach { add("Position #${it.id} updated") }
        proposal.newFacts.filter { it.selected }.forEach { add("Background: ${it.text}") }
        proposal.factEdits.filter { it.selected }.forEach { add("Background #${it.id} updated") }
        proposal.newNeeds.filter { it.selected }.forEach { add("Need: ${it.text}") }
        proposal.newCapabilities.filter { it.selected }.forEach { add("Capability: ${it.text}") }
        proposal.interactionEdits.filter { it.selected }.forEach { add("Interaction #${it.id} edited") }
        proposal.needEdits.filter { it.selected }.forEach { add("Need #${it.id} updated") }
        proposal.capabilityEdits.filter { it.selected }.forEach { add("Capability #${it.id} updated") }
    }
    Text("Saved", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    if (lines.isEmpty()) {
        Text("The note was stored verbatim.", style = MaterialTheme.typography.bodySmall)
    } else {
        lines.forEach { Text("\u2022 $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun TargetChoiceCard(
    choice: TargetChoiceState,
    snapshot: NetworkSnapshot,
    busy: Boolean,
    onChoose: (Long?) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Choose an existing person, or create a new profile.",
                style = MaterialTheme.typography.bodySmall,
            )
            choice.suggestions.forEach { person ->
                TextButton(onClick = { onChoose(person.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    val detail = snapshot.affiliationSummary(person.id)
                    Text(if (detail.isBlank()) person.name else "${person.name} \u2014 $detail")
                }
            }
            TextButton(onClick = { onChoose(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Create new person: ${choice.targetName}")
            }
        }
    }
}

/**
 * A re-filing the assistant worked out, waiting on the user to confirm it.
 *
 * Every candidate note is shown in full rather than summarised. Which note the
 * user meant is the one part of a move the app is guessing at, and a move
 * rewrites who a stored record belongs to, so the guess has to be visible and
 * changeable before it is acted on rather than described afterwards.
 */
@Composable
fun MoveCard(
    move: ChatAttachment.Move,
    busy: Boolean,
    onSelectNote: (Long) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onOpenPerson: (Long) -> Unit,
) {
    val plan = move.plan
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${plan.from.name} → ${plan.destinationName}" + if (plan.createsPerson) " (new person)" else "",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (move.done) {
                Text("Moved.", style = MaterialTheme.typography.bodyMedium)
                move.movedToPersonId?.let { personId ->
                    TextButton(onClick = { onOpenPerson(personId) }) { Text("Open ${plan.destinationName}") }
                }
            } else {
                plan.candidates.forEach { candidate ->
                    MoveCandidateRow(
                        candidate = candidate,
                        chosen = candidate.interaction.id == plan.selectedInteractionId,
                        enabled = !busy,
                        onChoose = { onSelectNote(candidate.interaction.id) },
                    )
                }
                Text(
                    "The note and every position, need, capability, and background record it created move together.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Profile details it changed on ${plan.from.name} stay there, because a changed field keeps no record of where it came from. Check them by hand afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                    Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Move")
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveCandidateRow(
    candidate: MoveCandidate,
    chosen: Boolean,
    enabled: Boolean,
    onChoose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onChoose),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = chosen, onClick = onChoose, enabled = enabled)
        Column(Modifier.padding(top = 12.dp)) {
            Text(
                formatAiDate(candidate.interaction.occurredAt) + linkedRecordsLabel(candidate.linkedRecords),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                candidate.interaction.note,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun linkedRecordsLabel(count: Int): String = when (count) {
    0 -> ""
    1 -> " · 1 linked record"
    else -> " · $count linked records"
}

/** Ranked matches with the stored evidence that produced each one. */
@Composable
fun SearchResultsCard(results: List<AiPersonSearchResult>, onOpenPerson: (Long) -> Unit) {
    if (results.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        results.forEach { result ->
            Card(
                Modifier.fillMaxWidth().clickable { onOpenPerson(result.person.id) },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(result.person.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(result.reasoning, style = MaterialTheme.typography.bodyMedium)
                    if (result.uncertainty.isNotBlank()) {
                        Text(
                            "Uncertainty: ${result.uncertainty}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    result.evidence.forEach { evidence ->
                        Column {
                            Text(
                                "${evidence.kind} \u00b7 ${formatAiDate(evidence.recordedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(evidence.text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
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

/** One proposed position. Organization and role stay separate so either can be corrected. */
@Composable
private fun AffiliationAddCard(item: AiAffiliationAdd, onChange: (AiAffiliationAdd) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(item.selected, { onChange(item.copy(selected = it)) })
                Text("New position", fontWeight = FontWeight.SemiBold)
            }
            OutlinedTextField(
                item.organization,
                { onChange(item.copy(organization = it)) },
                enabled = item.selected,
                label = { Text("Organization") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                item.role,
                { onChange(item.copy(role = it)) },
                enabled = item.selected,
                label = { Text("Role") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (item.selected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(item.current, { onChange(item.copy(current = it)) })
                    Text(if (item.current) "Current" else "Past", modifier = Modifier.padding(start = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(item.education, { onChange(item.copy(education = it)) })
                    Text(
                        if (item.education) "Studied here" else "Worked here",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FactEditCard(edit: AiFactEdit, before: String, onChange: (AiFactEdit) -> Unit) {
    SelectableTextEdit(
        edit.selected,
        "Edit background #${edit.id}",
        before,
        edit.text,
        { onChange(edit.copy(selected = it)) },
        { onChange(edit.copy(text = it)) },
    )
    if (edit.selected) DateField("Last confirmed", edit.lastConfirmedAt) { onChange(edit.copy(lastConfirmedAt = it)) }
}

@Composable
private fun AffiliationEditCard(edit: AiAffiliationEdit, before: String, onChange: (AiAffiliationEdit) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(edit.selected, { onChange(edit.copy(selected = it)) })
                Text("Edit position #${edit.id}", fontWeight = FontWeight.SemiBold)
            }
            if (before.isNotBlank()) Text("Before: $before", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                edit.organization,
                { onChange(edit.copy(organization = it)) },
                enabled = edit.selected,
                label = { Text("Organization") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                edit.role,
                { onChange(edit.copy(role = it)) },
                enabled = edit.selected,
                label = { Text("Role") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (edit.selected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(edit.current, { onChange(edit.copy(current = it)) })
                    Text(if (edit.current) "Current" else "Past", modifier = Modifier.padding(start = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(edit.education, { onChange(edit.copy(education = it)) })
                    Text(
                        if (edit.education) "Studied here" else "Worked here",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                DateField("Last confirmed", edit.lastConfirmedAt) { onChange(edit.copy(lastConfirmedAt = it)) }
            }
        }
    }
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
