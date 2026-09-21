package com.azizjon.network.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NeedItem
import com.azizjon.network.data.NeedStage
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.needsByStage

/**
 * Other people's needs across the whole network, for spotting one to help with.
 *
 * Opens on the ones still open that the user has not helped with. Marking one
 * helped or closing it only moves it to another list, so a mistaken tap is put
 * right from that list rather than lost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeedsScreen(
    snapshot: NetworkSnapshot,
    onOpenPerson: (Long) -> Unit,
    onSetHelped: (NeedEntity, Boolean) -> Unit,
    onSetActive: (NeedEntity, Boolean) -> Unit,
) {
    var stage by rememberSaveable { mutableStateOf(NeedStage.TO_HELP) }
    val board = remember(snapshot) { snapshot.needsByStage() }
    val shown = board.getValue(stage)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Needs")
                        Text("Open problems and goals across your network", style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NeedStage.entries.forEach { item ->
                    FilterChip(
                        selected = stage == item,
                        onClick = { stage = item },
                        label = { Text("${item.label} (${board.getValue(item).size})") },
                    )
                }
            }
            // One scroll position per list, so switching lists starts at the top.
            key(stage) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (shown.isEmpty()) {
                        item("empty") { EmptyNeeds(stage, nothingRecorded = board.values.all { it.isEmpty() }) }
                    }
                    items(shown, key = { it.need.id }) { item ->
                        NeedCard(
                            item = item,
                            onOpen = { onOpenPerson(item.person.id) },
                            onSetHelped = { helped -> onSetHelped(item.need, helped) },
                            onSetActive = { active -> onSetActive(item.need, active) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

private val NeedStage.label: String
    get() = when (this) {
        NeedStage.TO_HELP -> "To help"
        NeedStage.HELPED -> "Helped"
        NeedStage.CLOSED -> "Closed"
    }

@Composable
private fun NeedCard(
    item: NeedItem,
    onOpen: () -> Unit,
    onSetHelped: (Boolean) -> Unit,
    onSetActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClickLabel = "Open ${item.person.name}", onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp)) {
            Text(item.need.text, modifier = Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
            Text(
                "${item.person.name} · ${formatDate(item.need.lastConfirmedAt)}",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            helpedLabel(item.need)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when (item.stage) {
                    NeedStage.TO_HELP -> {
                        TextButton(onClick = { onSetActive(false) }) { Text("Close") }
                        TextButton(onClick = { onSetHelped(true) }) { Text("I helped") }
                    }
                    NeedStage.HELPED -> {
                        TextButton(onClick = { onSetActive(false) }) { Text("Close") }
                        TextButton(onClick = { onSetHelped(false) }) { Text("Not helped yet") }
                    }
                    NeedStage.CLOSED -> TextButton(onClick = { onSetActive(true) }) { Text("Reopen") }
                }
            }
        }
    }
}

@Composable
private fun EmptyNeeds(stage: NeedStage, nothingRecorded: Boolean) {
    if (nothingRecorded) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("No needs recorded yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("When someone mentions a problem or a goal, tell the assistant, or add it under Needs / goals on their page.")
            }
        }
        return
    }
    Text(
        when (stage) {
            NeedStage.TO_HELP -> "Nothing left to help with. Needs you helped with or closed are in the other lists."
            NeedStage.HELPED -> "Nothing here yet. Tap I helped on a need once you have done something about it."
            NeedStage.CLOSED -> "Nothing closed yet. Close a need once it is solved or no longer applies."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "You helped on 2026-09-21", or null while the user has not. */
internal fun helpedLabel(need: NeedEntity): String? = need.helpedAt?.let { "You helped on ${formatDate(it)}" }
