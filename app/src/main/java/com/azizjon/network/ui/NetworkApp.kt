package com.azizjon.network.ui

import androidx.compose.foundation.layout.Box
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
    CHAT("Assistant"),
    PEOPLE("People"),
    SETTINGS("Settings"),
}

@Composable
fun NetworkApp(viewModel: NetworkViewModel) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val composerDraft by viewModel.composerDraft.collectAsStateWithLifecycle()
    val searchConsentRequest by viewModel.searchConsentRequest.collectAsStateWithLifecycle()
    val gatewaySettingsState by viewModel.gatewaySettingsState.collectAsStateWithLifecycle()
    val speechFallbackAllowed by viewModel.speechFallbackAllowed.collectAsStateWithLifecycle()
    val feedbackState by viewModel.feedbackState.collectAsStateWithLifecycle()
    val feedbackTarget by viewModel.feedbackTarget.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var section by rememberSaveable { mutableStateOf(AppSection.CHAT) }
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
                    onAddAffiliation = viewModel::addAffiliation,
                    onAddFact = viewModel::addFact,
                    onDeleteInteraction = viewModel::deleteInteraction,
                    onMoveInteraction = { interactionId, destination ->
                        // Follow the note to its new home: seeing it land is the
                        // only confirmation that the right person was chosen.
                        viewModel.moveInteraction(interactionId, destination) { selectedPersonId = it }
                    },
                    onDeleteNeed = viewModel::deleteNeed,
                    onDeleteCapability = viewModel::deleteCapability,
                    onDeleteAffiliation = viewModel::deleteAffiliation,
                    onSetAffiliationCurrent = viewModel::setAffiliationCurrent,
                    onDeleteFact = viewModel::deleteFact,
                )
            } else {
                when (section) {
                    AppSection.CHAT -> ChatScreen(
                        chat = chat,
                        snapshot = snapshot,
                        draft = composerDraft,
                        searchConsentRequest = searchConsentRequest,
                        feedbackTarget = feedbackTarget,
                        speechFallbackAllowed = speechFallbackAllowed,
                        onDraftChange = viewModel::updateComposerDraft,
                        onSend = viewModel::sendChatMessage,
                        onAllowSpeechFallback = viewModel::allowSpeechFallbackForSession,
                        onOpenPerson = { selectedPersonId = it },
                        onChooseTarget = viewModel::chooseChatTarget,
                        onChangeProposalTarget = viewModel::changeChatProposalTarget,
                        onUpdateProposal = viewModel::updateChatProposal,
                        onDiscardProposal = viewModel::discardChatProposal,
                        onApplyProposal = viewModel::applyChatProposal,
                        onConfirmSearchConsent = viewModel::confirmSearchConsent,
                        onDismissSearchConsent = viewModel::dismissSearchConsent,
                        onReportMessage = viewModel::startFeedback,
                        onMoveInteraction = { interactionId, destination ->
                            viewModel.moveInteraction(interactionId, destination)
                        },
                        onDismissFeedback = viewModel::dismissFeedback,
                        onSubmitFeedback = viewModel::submitFeedback,
                        onNewChat = viewModel::startNewChat,
                    )
                    AppSection.PEOPLE -> PeopleScreen(
                        snapshot = snapshot,
                        onOpenPerson = { selectedPersonId = it },
                        onSavePerson = viewModel::savePerson,
                    )
                    AppSection.SETTINGS -> SettingsScreen(
                        backupState = backupState,
                        gatewaySettingsState = gatewaySettingsState,
                        updateState = updateState,
                        feedbackState = feedbackState,
                        onSaveAccessToken = viewModel::saveAccessToken,
                        onClearAccessToken = viewModel::clearAccessToken,
                        onRevokeAiSearchConsent = viewModel::revokeAiSearchConsent,
                        onSaveBackupConfig = viewModel::saveBackupConfig,
                        onBackupNow = viewModel::backupNow,
                        onRestore = viewModel::restoreFromGitHub,
                        onDeleteFeedback = viewModel::deleteFeedback,
                        onClearFeedback = viewModel::clearAllFeedback,
                    )
                }
            }
        }
    }
    UpdatePrompt(updateState)
}
