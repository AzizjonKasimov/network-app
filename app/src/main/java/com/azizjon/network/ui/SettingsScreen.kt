package com.azizjon.network.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.azizjon.network.backup.GitHubBackupConfig
import com.azizjon.network.ai.GatewayClient
import com.azizjon.network.ai.GatewaySettings
import com.azizjon.network.ai.GatewaySettingsState
import com.azizjon.network.feedback.AiFeedbackLabel
import com.azizjon.network.update.UpdatePromptState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    backupState: BackupUiState,
    gatewaySettingsState: GatewaySettingsState,
    updateState: UpdatePromptState,
    feedbackState: FeedbackUiState,
    onSaveAccessToken: (String) -> Unit,
    onClearAccessToken: () -> Unit,
    onRevokeAiSearchConsent: () -> Unit,
    onSaveBackupConfig: (GitHubBackupConfig) -> Unit,
    onBackupNow: () -> Unit,
    onRestore: (() -> Unit) -> Unit,
    onDeleteFeedback: (Long) -> Unit,
    onClearFeedback: () -> Unit,
) {
    val saved = backupState.config
    var owner by remember(saved) { mutableStateOf(saved.owner) }
    var repo by remember(saved) { mutableStateOf(saved.repo) }
    var branch by remember(saved) { mutableStateOf(saved.branch) }
    var path by remember(saved) { mutableStateOf(saved.path) }
    var token by remember(saved) { mutableStateOf(saved.token) }
    var passphrase by remember(saved) { mutableStateOf(saved.passphrase) }
    var autoBackup by remember(saved) { mutableStateOf(saved.autoBackup) }
    var accessToken by remember { mutableStateOf("") }
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    var confirmClearFeedback by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("AI gateway", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Natural-language capture sends only your draft and the selected person's non-contact context. A coverage review helps surface explicit facts before saving. AI search sends the disclosed active searchable network. Manual editing and local search remain available offline.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (gatewaySettingsState.tokenSaved) "Access token saved" else "Access token required",
                        fontWeight = FontWeight.SemiBold,
                        color = if (gatewaySettingsState.tokenSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Text("Gateway: ${GatewayClient.GATEWAY_BASE_URL}", style = MaterialTheme.typography.bodySmall)
                    Text("The gateway selects the model, so it can change without an app update.", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Use a token issued for this device only. A token stored on a compromised or rooted phone may be extracted; revoke it on the gateway if that happens.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = accessToken,
                        onValueChange = { if (it.length <= GatewaySettings.MAX_TOKEN_CHARACTERS) accessToken = it },
                        label = { Text(if (gatewaySettingsState.tokenSaved) "Replace access token" else "Access token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = accessToken.isNotBlank(),
                            onClick = {
                                onSaveAccessToken(accessToken)
                                accessToken = ""
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Save token") }
                        Button(
                            enabled = gatewaySettingsState.tokenSaved,
                            onClick = onClearAccessToken,
                            modifier = Modifier.weight(1f),
                        ) { Text("Remove token") }
                    }
                    HorizontalDivider()
                    Text(
                        if (gatewaySettingsState.fullNetworkSearchConsent) "Full-network AI search consent accepted" else "Full-network AI search consent not accepted",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Search excludes contact values, archived people, closed needs, and inactive capabilities.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (gatewaySettingsState.fullNetworkSearchConsent) {
                        TextButton(onClick = onRevokeAiSearchConsent) { Text("Revoke search consent") }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Encrypted GitHub backup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "The app encrypts all network data on-device before upload and refuses public repositories. The token and passphrase stay in Android encrypted preferences and are never included in the backup.",
                style = MaterialTheme.typography.bodyMedium,
            )
            BackupStatusCard(backupState)
            SettingsField(owner, { owner = it }, "GitHub owner")
            SettingsField(repo, { repo = it }, "Private data repository")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsField(branch, { branch = it }, "Branch", Modifier.weight(1f))
                SettingsField(path, { path = it }, "Backup path", Modifier.weight(2f))
            }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("GitHub fine-grained token") },
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("Grant Contents read/write only to the private data repository") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Backup encryption passphrase") },
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("At least 12 characters; you will need it after reinstalling") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(autoBackup, { autoBackup = it })
                Text("Automatically back up after changes")
            }
            Button(
                onClick = {
                    onSaveBackupConfig(
                        GitHubBackupConfig(owner, repo, branch, path, token, passphrase, autoBackup),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save backup settings") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !backupState.busy, onClick = onBackupNow, modifier = Modifier.weight(1f)) {
                    Text(if (backupState.busy) "Working…" else "Back up now")
                }
                Button(enabled = !backupState.busy, onClick = { confirmRestore = true }, modifier = Modifier.weight(1f)) {
                    Text("Restore")
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Assistant feedback", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Report a wrong answer from the Assistant tab and it is stored here with a copy of the message and the response. Reports travel inside the encrypted backup, so backing up is all it takes to hand them over. Delete one once its fault has been fixed.",
                style = MaterialTheme.typography.bodyMedium,
            )
            FeedbackCard(
                state = feedbackState,
                onClear = { confirmClearFeedback = true },
                onDelete = onDeleteFeedback,
            )

            Spacer(Modifier.height(8.dp))
            Text("App updates", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Installed ${updateState.currentVersionLabel}")
                    Text("Updates are checked silently at launch from the signed GitHub release channel.", style = MaterialTheme.typography.bodySmall)
                    Button(
                        enabled = !updateState.checking,
                        onClick = { updateState.checkForUpdates(showResult = true) },
                    ) { Text(if (updateState.checking) "Checking…" else "Check for updates") }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmClearFeedback) {
        AlertDialog(
            onDismissRequest = { confirmClearFeedback = false },
            title = { Text("Delete all reports?") },
            text = { Text("Every stored report is deleted. Your people and records are not affected. This cannot be undone, and the next backup will no longer carry them.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearFeedback = false
                    onClearFeedback()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClearFeedback = false }) { Text("Cancel") } },
        )
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Replace local network data?") },
            text = { Text("Restore decrypts the GitHub backup and replaces every local person and linked record. This cannot be undone unless the current data is backed up first.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    onRestore {}
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }
}

/**
 * What has been reported, and the one thing left to do with it.
 *
 * The list is deliberately shown in full rather than summarised: a report is
 * only useful if the owner can still recognise it, and recognising it is also
 * how a mistaken one gets deleted before it reaches a backup.
 */
@Composable
private fun FeedbackCard(
    state: FeedbackUiState,
    onClear: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (state.reports.isEmpty()) "No reports yet" else "${state.reports.size} report(s) collected",
                fontWeight = FontWeight.SemiBold,
            )
            Button(
                enabled = state.reports.isNotEmpty(),
                onClick = onClear,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Delete all") }
            state.reports.forEach { report ->
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            AiFeedbackLabel.titleFor(report.label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${report.stage} · ${formatTimestamp(report.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (report.note.isNotBlank()) {
                            Text(report.note, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { onDelete(report.id) }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun BackupStatusCard(state: BackupUiState) {
    val status = state.status
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when {
                    status.backupNeeded -> "Backup required"
                    status.lastBackupAt > 0 -> "Local data is backed up"
                    else -> "No successful backup yet"
                },
                fontWeight = FontWeight.SemiBold,
                color = if (status.backupNeeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            if (status.lastBackupAt > 0) Text("Last success: ${formatTimestamp(status.lastBackupAt)}", style = MaterialTheme.typography.bodySmall)
            if (status.lastAttemptAt > 0) Text("Last attempt: ${formatTimestamp(status.lastAttemptAt)}", style = MaterialTheme.typography.bodySmall)
            if (status.lastError.isNotBlank()) Text(status.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(value, onValueChange, modifier = modifier, label = { Text(label) }, singleLine = true)
}

private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private fun formatTimestamp(value: Long): String =
    Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(timestampFormatter)
