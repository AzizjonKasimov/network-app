package com.azizjon.network.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azizjon.network.update.UpdatePrompt
import com.azizjon.network.update.rememberUpdatePromptState

private enum class AppSection(val label: String) {
    PEOPLE("People"),
    SEARCH("Match"),
    SETTINGS("Settings"),
}

@Composable
fun NetworkApp(viewModel: NetworkViewModel) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val aiDraft by viewModel.aiDraft.collectAsStateWithLifecycle()
    val aiCaptureState by viewModel.aiCaptureState.collectAsStateWithLifecycle()
    val aiSearchState by viewModel.aiSearchState.collectAsStateWithLifecycle()
    val gatewaySettingsState by viewModel.gatewaySettingsState.collectAsStateWithLifecycle()
    val speechFallbackAllowed by viewModel.speechFallbackAllowed.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var section by rememberSaveable { mutableStateOf(AppSection.PEOPLE) }
    var selectedPersonId by rememberSaveable { mutableStateOf<Long?>(null) }
    val updateState = rememberUpdatePromptState(viewModel::showMessage)

    LaunchedEffect(message) {
        val value = message ?: return@LaunchedEffect
        snackbar.showSnackbar(value)
        viewModel.clearMessage()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selectedPersonId == null) {
                NavigationBar {
                    AppSection.entries.forEach { item ->
                        NavigationBarItem(
                            selected = section == item,
                            onClick = { section = item },
                            icon = { Text(item.label.take(1)) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            val selected = selectedPersonId?.let(snapshot::person)
            if (selected != null) {
                PersonDetailScreen(
                    person = selected,
                    snapshot = snapshot,
                    onBack = { selectedPersonId = null },
                    onSave = viewModel::savePerson,
                    onDeletePerson = { person ->
                        viewModel.deletePerson(person) { selectedPersonId = null }
                    },
                    onAddInteraction = viewModel::addInteraction,
                    onAddNeed = viewModel::addNeed,
                    onAddCapability = viewModel::addCapability,
                    onDeleteInteraction = viewModel::deleteInteraction,
                    onDeleteNeed = viewModel::deleteNeed,
                    onDeleteCapability = viewModel::deleteCapability,
                )
            } else {
                when (section) {
                    AppSection.PEOPLE -> PeopleScreen(
                        snapshot = snapshot,
                        aiDraft = aiDraft,
                        aiCaptureState = aiCaptureState,
                        onOpenPerson = { selectedPersonId = it },
                        onSavePerson = viewModel::savePerson,
                        onAiDraftChange = viewModel::updateAiDraft,
                        speechFallbackAllowed = speechFallbackAllowed,
                        onAllowSpeechFallback = viewModel::allowSpeechFallbackForSession,
                        onInterpretAiDraft = viewModel::interpretAiDraft,
                        onChooseAiTarget = viewModel::chooseAiTarget,
                        onChangeAiTarget = viewModel::changeAiProposalTarget,
                        onUpdateAiProposal = viewModel::updateAiProposal,
                        onCancelAiCapture = viewModel::cancelAiCapture,
                        onApplyAiProposal = { viewModel.applyAiProposal { selectedPersonId = it } },
                    )
                    AppSection.SEARCH -> SearchScreen(
                        snapshot = snapshot,
                        aiSearchState = aiSearchState,
                        gatewaySettingsState = gatewaySettingsState,
                        onOpenPerson = { selectedPersonId = it },
                        onAcceptAiSearchConsent = viewModel::acceptAiSearchConsent,
                        onSearchWithAi = viewModel::searchWithAi,
                        speechFallbackAllowed = speechFallbackAllowed,
                        onAllowSpeechFallback = viewModel::allowSpeechFallbackForSession,
                    )
                    AppSection.SETTINGS -> SettingsScreen(
                        backupState = backupState,
                        gatewaySettingsState = gatewaySettingsState,
                        updateState = updateState,
                        onSaveAccessToken = viewModel::saveAccessToken,
                        onClearAccessToken = viewModel::clearAccessToken,
                        onRevokeAiSearchConsent = viewModel::revokeAiSearchConsent,
                        onSaveBackupConfig = viewModel::saveBackupConfig,
                        onBackupNow = viewModel::backupNow,
                        onRestore = viewModel::restoreFromGitHub,
                    )
                }
            }
        }
    }
    UpdatePrompt(updateState)
}
