package com.azizjon.network.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azizjon.network.data.AffiliationEntity
import com.azizjon.network.data.CapabilityEntity
import com.azizjon.network.data.FactEntity
import com.azizjon.network.data.InteractionEntity
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonDraft
import com.azizjon.network.data.PersonEntity
import com.azizjon.network.search.NetworkMatcher
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    snapshot: NetworkSnapshot,
    onOpenPerson: (Long) -> Unit,
    onSavePerson: (PersonDraft, (Long) -> Unit) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val people = snapshot.people.filterNot { it.archived }
    // Deterministic local matching, so browsing and finding both keep working
    // without a token, consent, or a network round trip.
    val matches = remember(snapshot, query) {
        if (query.isBlank()) emptyList() else NetworkMatcher.search(snapshot, query)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Your network")
                        Text("${people.size} people", style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Text("+") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search your network") },
                supportingText = { Text("Local matching only. Ask the assistant for evidence-backed suggestions.") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (query.isNotBlank()) {
                    if (matches.isEmpty()) {
                        item("no-matches") {
                            Text("No local matches. Try different words, or ask the assistant.")
                        }
                    }
                    items(matches, key = { "match-${it.person.id}" }) { result ->
                        Card(Modifier.fillMaxWidth().clickable { onOpenPerson(result.person.id) }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    result.person.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                result.evidence.take(3).forEach { evidence ->
                                    Column {
                                        Text(
                                            "${evidence.kind} · ${formatDate(evidence.recordedAt)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(evidence.text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                } else if (people.isEmpty()) {
                    item("empty") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Start with one person",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text("Describe someone on the Assistant tab, or use + to add them manually.")
                            }
                        }
                    }
                } else {
                    items(people, key = { it.id }) { person ->
                        PersonCard(person, snapshot, onClick = { onOpenPerson(person.id) })
                    }
                }
            }
        }
    }
    if (adding) {
        PersonEditorDialog(
            person = null,
            onDismiss = { adding = false },
            onSave = { draft -> onSavePerson(draft) { adding = false } },
        )
    }
}

@Composable
private fun PersonCard(person: PersonEntity, snapshot: NetworkSnapshot, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(person.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (person.isSelf) {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                        Text("You", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            val work = snapshot.affiliationSummary(person.id)
            if (work.isNotBlank()) Text(work, style = MaterialTheme.typography.bodyMedium)
            if (person.relationship.isNotBlank()) Text(person.relationship, style = MaterialTheme.typography.bodySmall)
            Text(
                "${snapshot.capabilitiesFor(person.id).size} capabilities · ${snapshot.needsFor(person.id).size} needs · ${snapshot.interactionsFor(person.id).size} interactions",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    person: PersonEntity,
    snapshot: NetworkSnapshot,
    onBack: () -> Unit,
    onSave: (PersonDraft, (Long) -> Unit) -> Unit,
    onDeletePerson: (PersonEntity) -> Unit,
    onAddInteraction: (Long, String) -> Unit,
    onAddNeed: (Long, String) -> Unit,
    onAddCapability: (Long, String) -> Unit,
    onAddAffiliation: (Long, String, String, Boolean) -> Unit,
    onAddFact: (Long, String) -> Unit,
    onDeleteInteraction: (InteractionEntity) -> Unit,
    onDeleteNeed: (NeedEntity) -> Unit,
    onDeleteCapability: (CapabilityEntity) -> Unit,
    onDeleteAffiliation: (AffiliationEntity) -> Unit,
    onSetAffiliationCurrent: (AffiliationEntity, Boolean) -> Unit,
    onDeleteFact: (FactEntity) -> Unit,
) {
    var editing by rememberSaveable(person.id) { mutableStateOf(false) }
    var addKind by rememberSaveable(person.id) { mutableStateOf<RecordKind?>(null) }
    var confirmDelete by rememberSaveable(person.id) { mutableStateOf(false) }
    var addingAffiliation by rememberSaveable(person.id) { mutableStateOf(false) }
    val affiliations = snapshot.affiliationsFor(person.id)
    val facts = snapshot.factsFor(person.id)
    val interactions = snapshot.interactionsFor(person.id)
    val needs = snapshot.needsFor(person.id)
    val capabilities = snapshot.capabilitiesFor(person.id)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(person.name) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { editing = true }) { Text("Edit") }
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ProfileSummary(person, snapshot.affiliationSummary(person.id))
            RecordSection("Positions", affiliations.size, { addingAffiliation = true }) {
                affiliations.forEach { item ->
                    AffiliationCard(
                        item = item,
                        onDelete = { onDeleteAffiliation(item) },
                        onSetCurrent = { current -> onSetAffiliationCurrent(item, current) },
                    )
                }
            }
            RecordSection("Background", facts.size, { addKind = RecordKind.FACT }) {
                facts.forEach { item ->
                    RecordCard(item.text, item.lastConfirmedAt, { onDeleteFact(item) })
                }
            }
            RecordSection("Capabilities / resources", capabilities.size, { addKind = RecordKind.CAPABILITY }) {
                capabilities.forEach { item ->
                    RecordCard(item.text, item.lastConfirmedAt, { onDeleteCapability(item) }, if (item.active) "" else "Inactive")
                }
            }
            RecordSection("Needs / goals", needs.size, { addKind = RecordKind.NEED }) {
                needs.forEach { item ->
                    RecordCard(item.text, item.lastConfirmedAt, { onDeleteNeed(item) }, if (item.status == "active") "" else "Closed")
                }
            }
            RecordSection("Interactions", interactions.size, { addKind = RecordKind.INTERACTION }) {
                interactions.forEach { item ->
                    RecordCard(
                        item.note,
                        item.occurredAt,
                        { onDeleteInteraction(item) },
                        if (item.origin == InteractionEntity.ORIGIN_AI_REVIEWED) "AI-reviewed capture" else "",
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (editing) {
        PersonEditorDialog(
            person = person,
            onDismiss = { editing = false },
            onSave = { draft -> onSave(draft) { editing = false } },
        )
    }
    addKind?.let { kind ->
        AddRecordDialog(
            kind = kind,
            onDismiss = { addKind = null },
            onSave = { text ->
                when (kind) {
                    RecordKind.FACT -> onAddFact(person.id, text)
                    RecordKind.CAPABILITY -> onAddCapability(person.id, text)
                    RecordKind.NEED -> onAddNeed(person.id, text)
                    RecordKind.INTERACTION -> onAddInteraction(person.id, text)
                }
                addKind = null
            },
        )
    }
    if (addingAffiliation) {
        AddAffiliationDialog(
            onDismiss = { addingAffiliation = false },
            onSave = { organization, role, education ->
                onAddAffiliation(person.id, organization, role, education)
                addingAffiliation = false
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${person.name}?") },
            text = { Text("This permanently deletes the person and all linked notes from this device.") },
            confirmButton = { TextButton(onClick = { onDeletePerson(person) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AffiliationCard(
    item: AffiliationEntity,
    onDelete: () -> Unit,
    onSetCurrent: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            val state = when {
                item.isEducation && item.current -> "Studying"
                item.isEducation -> "Studied"
                item.current -> "Current"
                else -> "Past"
            }
            Text(
                "$state · confirmed ${formatDate(item.lastConfirmedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(item.current, onSetCurrent)
                Text(
                    if (item.current) "Still there" else if (item.isEducation) "Finished" else "Left",
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun AddAffiliationDialog(onDismiss: () -> Unit, onSave: (String, String, Boolean) -> Unit) {
    var organization by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var education by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a position") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Someone can hold several at once. Add one for each.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = organization,
                    onValueChange = { organization = it },
                    label = { Text("Organization") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = role,
                    onValueChange = { role = it },
                    label = { Text(if (education) "Qualification" else "Role") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(education, { education = it })
                    Text(
                        if (education) "Studied here" else "Worked here",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = organization.isNotBlank() || role.isNotBlank(),
                onClick = { onSave(organization, role, education) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProfileSummary(person: PersonEntity, work: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (work.isNotBlank()) Text(work, style = MaterialTheme.typography.titleMedium)
            listOf(person.location, person.contact, person.relationship, person.tags, person.notes)
                .filter { it.isNotBlank() }
                .forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun RecordSection(title: String, count: Int, onAdd: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$title ($count)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onAdd) { Text("Add") }
        }
        if (count == 0) Text("Nothing recorded yet", style = MaterialTheme.typography.bodySmall)
        content()
    }
}

@Composable
private fun RecordCard(text: String, timestamp: Long, onDelete: () -> Unit, status: String = "") {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(text)
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatDate(timestamp), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

private enum class RecordKind(val title: String, val hint: String) {
    FACT("Add background", "A fact worth remembering about them"),
    CAPABILITY("Add capability or resource", "What could this person help with?"),
    NEED("Add need or goal", "What are they trying to solve or achieve?"),
    INTERACTION("Add interaction", "What did you discuss?"),
}

@Composable
private fun AddRecordDialog(kind: RecordKind, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable(kind) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kind.title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(kind.hint) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PersonEditorDialog(person: PersonEntity?, onDismiss: () -> Unit, onSave: (PersonDraft) -> Unit) {
    var name by rememberSaveable(person?.id) { mutableStateOf(person?.name.orEmpty()) }
    var location by rememberSaveable(person?.id) { mutableStateOf(person?.location.orEmpty()) }
    var contact by rememberSaveable(person?.id) { mutableStateOf(person?.contact.orEmpty()) }
    var relationship by rememberSaveable(person?.id) { mutableStateOf(person?.relationship.orEmpty()) }
    var tags by rememberSaveable(person?.id) { mutableStateOf(person?.tags.orEmpty()) }
    var notes by rememberSaveable(person?.id) { mutableStateOf(person?.notes.orEmpty()) }
    var isSelf by rememberSaveable(person?.id) { mutableStateOf(person?.isSelf ?: false) }
    var archived by rememberSaveable(person?.id) { mutableStateOf(person?.archived ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (person == null) "Add person" else "Edit person") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormField(name, { name = it }, "Name", true)
                FormField(location, { location = it }, "Location")
                FormField(contact, { contact = it }, "Contact details")
                FormField(relationship, { relationship = it }, "How you know them")
                FormField(tags, { tags = it }, "Tags")
                FormField(notes, { notes = it }, "Background notes", minLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(isSelf, { isSelf = it })
                    Spacer(Modifier.width(8.dp))
                    Text("This is me")
                }
                if (person != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(archived, { archived = it })
                        Text("Archived")
                    }
                }
                Text(
                    "Add jobs and companies under Positions, so someone can hold several at once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        PersonDraft(
                            id = person?.id ?: 0,
                            name = name,
                            location = location,
                            contact = contact,
                            relationship = relationship,
                            tags = tags,
                            notes = notes,
                            isSelf = isSelf,
                            archived = archived,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    required: Boolean = false,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label + if (required) " *" else "") },
        modifier = Modifier.fillMaxWidth(),
        minLines = minLines,
    )
}

private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private fun formatDate(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().format(dayFormatter)
