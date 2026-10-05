package com.azizjon.network.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.azizjon.network.checkin.ChatNotificationListener
import com.azizjon.network.checkin.ChatSources
import com.azizjon.network.checkin.CheckinEntity
import com.azizjon.network.checkin.CheckinList
import com.azizjon.network.checkin.CheckinSettingsState
import com.azizjon.network.checkin.MissingItem
import com.azizjon.network.checkin.NewPersonItem
import com.azizjon.network.checkin.StaleItem
import com.azizjon.network.checkin.TalkedItem
import com.azizjon.network.data.AffiliationEntity
import com.azizjon.network.data.NeedEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The evening check-in: who the user met or talked to that the network does not
 * know about yet, and what saved there may have gone stale.
 *
 * Every question has a way to say "not now" and "never", so the list only ever
 * holds what the user still wants to deal with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckinScreen(
    list: CheckinList,
    settings: CheckinSettingsState,
    onOpenPerson: (Long) -> Unit,
    onAddPerson: (NewPersonItem) -> Unit,
    onAddNote: (String, List<String>) -> Unit,
    onPeopleStatus: (List<String>, String) -> Unit,
    onSkipAllNew: () -> Unit,
    onNeedStillOpen: (NeedEntity) -> Unit,
    onNeedDone: (NeedEntity) -> Unit,
    onPositionStill: (AffiliationEntity) -> Unit,
    onPositionLeft: (AffiliationEntity) -> Unit,
    onRecordStatus: (String, String) -> Unit,
    onSetEvening: (Boolean, Int) -> Unit,
    onSetContacts: (Boolean) -> Unit,
    onSetChats: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    var contactsAllowed by remember { mutableStateOf(contactsPermitted(context)) }
    var listenerAllowed by remember { mutableStateOf(listenerEnabled(context)) }
    var showAccessHelp by rememberSaveable { mutableStateOf(false) }
    var confirmSkipAll by rememberSaveable { mutableStateOf(false) }
    val now = remember(list) { System.currentTimeMillis() }

    // Permissions are granted in other screens; pick up the answer on return.
    LifecycleResumeEffect(Unit) {
        contactsAllowed = contactsPermitted(context)
        listenerAllowed = listenerEnabled(context)
        onRefresh()
        onPauseOrDispose { }
    }

    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        contactsAllowed = granted
        if (granted) {
            onSetContacts(true)
        } else {
            Toast.makeText(context, "Contacts stay off. You can allow them in the app's settings.", Toast.LENGTH_LONG).show()
        }
    }
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            onSetEvening(true, settings.eveningMinutes)
        } else {
            Toast.makeText(context, "Without notifications the evening reminder cannot appear.", Toast.LENGTH_LONG).show()
        }
    }

    val sources = SourceControls(
        settings = settings,
        contactsAllowed = contactsAllowed,
        listenerAllowed = listenerAllowed,
        onEvening = { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onSetEvening(enabled, settings.eveningMinutes)
            }
        },
        onPickTime = {
            TimePickerDialog(
                context,
                { _, hour, minute -> onSetEvening(settings.eveningEnabled, hour * 60 + minute) },
                settings.eveningMinutes / 60,
                settings.eveningMinutes % 60,
                DateFormat.is24HourFormat(context),
            ).show()
        },
        onContacts = { enabled ->
            if (enabled && !contactsPermitted(context)) contactsLauncher.launch(Manifest.permission.READ_CONTACTS) else onSetContacts(enabled)
        },
        onChats = { enabled ->
            onSetChats(enabled)
            if (enabled && !listenerEnabled(context)) showAccessHelp = true
        },
        onAllowChats = { showAccessHelp = true },
    )

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Check-in")
                    Text("People you met, and what may have changed", style = MaterialTheme.typography.labelMedium)
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val nothingOn = !settings.eveningEnabled && !settings.contactsEnabled && !settings.chatsEnabled
            if (nothingOn) item("sources-first") { SourcesCard(sources, intro = true) }
            if (list.count == 0) item("empty") { EmptyCheckin(nothingOn) }

            section("New people", list.newPeople.size, action = if (list.newPeople.size > 3) "Skip all" to { confirmSkipAll = true } else null)
            items(list.newPeople, key = { "new-" + it.refs.first() }) { item ->
                QuestionCard(
                    title = item.name,
                    lines = listOf(seenLine(item.sources, item.lastSeenAt, now), "Not in your network yet."),
                    actions = listOf(
                        "Add" to { onAddPerson(item) },
                        "Later" to { onPeopleStatus(item.refs, CheckinEntity.STATUS_LATER) },
                        "Never" to { onPeopleStatus(item.refs, CheckinEntity.STATUS_NEVER) },
                    ),
                    modifier = Modifier.animateItem(),
                )
            }

            section("Talked to recently", list.talkedTo.size)
            items(list.talkedTo, key = { "talked-" + it.person.id }) { item ->
                QuestionCard(
                    title = item.person.name,
                    lines = listOf(seenLine(item.sources, item.lastSeenAt, now), "Anything new to remember?"),
                    onTitle = { onOpenPerson(item.person.id) },
                    actions = listOf(
                        "Add a note" to { onAddNote(item.person.name, item.refs) },
                        "Nothing new" to { onPeopleStatus(item.refs, CheckinEntity.STATUS_DONE) },
                    ),
                    modifier = Modifier.animateItem(),
                )
            }

            section("Still true?", list.stillTrue.size)
            items(list.stillTrue, key = { it.ref }) { item ->
                StaleCard(item, onOpenPerson, onNeedStillOpen, onNeedDone, onPositionStill, onPositionLeft, onRecordStatus, Modifier.animateItem())
            }

            section("Missing details", list.missingInfo.size)
            items(list.missingInfo, key = { it.ref }) { item ->
                QuestionCard(
                    title = item.person.name,
                    lines = listOf("Added ${daysAgo(item.person.createdAt, now)}.", "Where do they work or study?"),
                    onTitle = { onOpenPerson(item.person.id) },
                    actions = listOf(
                        "Tell" to { onAddNote(item.person.name, emptyList()) },
                        "Later" to { onRecordStatus(item.ref, CheckinEntity.STATUS_LATER) },
                        "Don't know" to { onRecordStatus(item.ref, CheckinEntity.STATUS_NEVER) },
                    ),
                    modifier = Modifier.animateItem(),
                )
            }

            if (!nothingOn) item("sources") { SourcesCard(sources, intro = false) }
        }
    }

    if (showAccessHelp) {
        NotificationAccessDialog(
            onOpenAccess = {
                showAccessHelp = false
                openListenerSettings(context)
            },
            onOpenAppInfo = {
                showAccessHelp = false
                openAppInfo(context)
            },
            onDismiss = { showAccessHelp = false },
        )
    }
    if (confirmSkipAll) {
        AlertDialog(
            onDismissRequest = { confirmSkipAll = false },
            title = { Text("Skip all ${list.newPeople.size}?") },
            text = { Text("None of them will be suggested again. People you save or who write to you from now on still will be.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSkipAll = false
                    onSkipAllNew()
                }) { Text("Skip all") }
            },
            dismissButton = { TextButton(onClick = { confirmSkipAll = false }) { Text("Cancel") } },
        )
    }
}

private class SourceControls(
    val settings: CheckinSettingsState,
    val contactsAllowed: Boolean,
    val listenerAllowed: Boolean,
    val onEvening: (Boolean) -> Unit,
    val onPickTime: () -> Unit,
    val onContacts: (Boolean) -> Unit,
    val onChats: (Boolean) -> Unit,
    val onAllowChats: () -> Unit,
)

private fun LazyListScope.section(title: String, count: Int, action: Pair<String, () -> Unit>? = null) {
    if (count == 0) return
    item("section-$title") {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$title ($count)", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
        }
    }
}

@Composable
private fun QuestionCard(
    title: String,
    lines: List<String>,
    actions: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
    onTitle: (() -> Unit)? = null,
) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp)) {
            Text(
                title,
                modifier = if (onTitle != null) Modifier.clickable(onClickLabel = "Open $title", onClick = onTitle) else Modifier,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (onTitle != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            lines.forEach { line ->
                Text(line, modifier = Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                actions.forEach { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
            }
        }
    }
}

@Composable
private fun StaleCard(
    item: StaleItem,
    onOpenPerson: (Long) -> Unit,
    onNeedStillOpen: (NeedEntity) -> Unit,
    onNeedDone: (NeedEntity) -> Unit,
    onPositionStill: (AffiliationEntity) -> Unit,
    onPositionLeft: (AffiliationEntity) -> Unit,
    onRecordStatus: (String, String) -> Unit,
    modifier: Modifier,
) {
    val name = if (item.person.isSelf) "You" else item.person.name
    val later = "Later" to { onRecordStatus(item.ref, CheckinEntity.STATUS_LATER) }
    when (item) {
        is StaleItem.Need -> QuestionCard(
            title = name,
            lines = listOf(item.need.text, "Still looking? Last checked ${formatDate(item.since)}."),
            onTitle = { onOpenPerson(item.person.id) },
            actions = listOf(
                "Still open" to { onNeedStillOpen(item.need) },
                "Done" to { onNeedDone(item.need) },
                later,
            ),
            modifier = modifier,
        )
        is StaleItem.Position -> QuestionCard(
            title = name,
            lines = listOf(item.position.label, "Still there? Last checked ${formatDate(item.since)}."),
            onTitle = { onOpenPerson(item.person.id) },
            actions = listOf(
                "Still there" to { onPositionStill(item.position) },
                "Left" to { onPositionLeft(item.position) },
                later,
            ),
            modifier = modifier,
        )
    }
}

@Composable
private fun SourcesCard(controls: SourceControls, intro: Boolean) {
    val settings = controls.settings
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (intro) "Set up your check-in" else "Check-in settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (intro) {
                Text("Turn on what should remind you. Everything stays on this phone until you choose to tell the assistant about someone.")
            }
            SourceRow(
                title = "Evening reminder",
                detail = "Every day at ${clock(settings.eveningMinutes)}, if there is anything to look at or to ask.",
                checked = settings.eveningEnabled,
                onCheckedChange = controls.onEvening,
            )
            TextButton(onClick = controls.onPickTime, modifier = Modifier.padding(start = 0.dp)) { Text("Change the time") }
            HorizontalDivider()
            SourceRow(
                title = "Phone contacts",
                detail = "Asks about numbers you save. Only names are read.",
                checked = settings.contactsEnabled && controls.contactsAllowed,
                onCheckedChange = controls.onContacts,
            )
            HorizontalDivider()
            SourceRow(
                title = "Chat apps",
                detail = "Asks about people who write to you on Telegram, WhatsApp, KakaoTalk and LinkedIn. " +
                    "Only who wrote and when, never the message. Group chats are skipped.",
                checked = settings.chatsEnabled,
                onCheckedChange = controls.onChats,
            )
            if (settings.chatsEnabled && !controls.listenerAllowed) {
                Text(
                    "Notification access is still off, so chats cannot be noticed yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = controls.onAllowChats) { Text("Turn on notification access") }
            }
        }
    }
}

@Composable
private fun SourceRow(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun EmptyCheckin(nothingOn: Boolean) {
    Text(
        if (nothingOn) {
            "Nothing to check yet. Turn on a reminder above, or tell the assistant about someone you met."
        } else {
            "All caught up. New people and anything that may have changed will show up here."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NotificationAccessDialog(onOpenAccess: () -> Unit, onOpenAppInfo: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn on notification access") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Tap Open settings and switch Network App on.")
                Text(
                    "2. If Android says it is a restricted setting, tap App info, then the ⋮ menu at the top right, " +
                        "choose Allow restricted settings, and do step 1 again. Android asks this because the app " +
                        "is not from the Play Store.",
                )
                Text(
                    "Android shows the app every notification, but it only looks at Telegram, WhatsApp, KakaoTalk " +
                        "and LinkedIn, and keeps only who wrote and when.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onOpenAccess) { Text("Open settings") } },
        dismissButton = {
            Row {
                TextButton(onClick = onOpenAppInfo) { Text("App info") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** "Saved in your phone · today", "Wrote on KakaoTalk, saved in your phone · 3 days ago". */
private fun seenLine(sources: List<String>, at: Long, now: Long): String {
    val where = sources.joinToString(", ") { source ->
        if (source == CheckinEntity.SOURCE_CONTACTS) "saved in your phone" else "wrote on ${ChatSources.label(source)}"
    }
    return where.replaceFirstChar(Char::uppercaseChar) + " · " + daysAgo(at, now)
}

private fun daysAgo(at: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val days = ChronoUnit.DAYS.between(dayOf(at, zone), dayOf(now, zone))
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        days < 30L -> "$days days ago"
        else -> "on ${formatDate(at)}"
    }
}

private fun dayOf(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

private fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun contactsPermitted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

private fun listenerEnabled(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

/** The switch for this app's listener where Android has one, else the list of all of them. */
private fun openListenerSettings(context: Context) {
    val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, ChatNotificationListener::class.java).flattenToString(),
        )
    } else {
        list
    }
    runCatching { context.startActivity(detail) }.onFailure { runCatching { context.startActivity(list) } }
}

private fun openAppInfo(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
}
