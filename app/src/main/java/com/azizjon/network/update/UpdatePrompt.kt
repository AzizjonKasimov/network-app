package com.azizjon.network.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class UpdatePromptState internal constructor(
    private val manager: UpdateManager,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
) {
    val currentVersionLabel: String = manager.currentVersionLabel()
    var info by mutableStateOf<UpdateInfo?>(null)
        private set
    var progress by mutableIntStateOf(-1)
        private set
    var checking by mutableStateOf(false)
        private set

    fun checkForUpdates(showResult: Boolean = false) {
        if (checking || progress >= 0) return
        scope.launch {
            checking = true
            try {
                when (val result = manager.checkLatest()) {
                    is UpdateCheckResult.Available -> info = result.info
                    UpdateCheckResult.UpToDate -> if (showResult) onMessage("App is up to date")
                    UpdateCheckResult.Unavailable -> if (showResult) onMessage("Could not check for updates")
                }
            } catch (_: Exception) {
                if (showResult) onMessage("Could not check for updates")
            } finally {
                checking = false
            }
        }
    }

    fun dismiss() {
        if (progress < 0) info = null
    }

    fun installUpdate() {
        val update = info ?: return
        if (progress >= 0) return
        scope.launch {
            progress = 0
            runCatching { manager.downloadApk(update) { progress = it } }
                .onSuccess { manager.installApk(it) }
                .onFailure { onMessage("Update download failed") }
            progress = -1
        }
    }
}

@Composable
fun rememberUpdatePromptState(onMessage: (String) -> Unit = {}): UpdatePromptState {
    val context = LocalContext.current
    val manager = remember { UpdateManager(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val latestMessage = rememberUpdatedState(onMessage)
    return remember(manager, scope) {
        UpdatePromptState(manager, scope) { latestMessage.value(it) }
    }
}

@Composable
fun UpdatePrompt(state: UpdatePromptState) {
    LaunchedEffect(state) { state.checkForUpdates() }
    val update = state.info ?: return
    AlertDialog(
        onDismissRequest = state::dismiss,
        title = { Text("Update available") },
        text = {
            Column {
                Text("Version ${update.versionName} is ready to install.")
                if (update.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(update.notes, style = MaterialTheme.typography.bodySmall)
                }
                if (state.progress >= 0) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Downloading… ${state.progress}%", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = state.progress < 0, onClick = state::installUpdate) { Text("Update") }
        },
        dismissButton = {
            if (state.progress < 0) TextButton(onClick = state::dismiss) { Text("Later") }
        },
    )
}
