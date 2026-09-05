package com.azizjon.network.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.azizjon.network.NetworkApplication
import com.azizjon.network.ai.AiCaptureState
import com.azizjon.network.ai.AiSearchState
import com.azizjon.network.ai.GatewayException
import com.azizjon.network.ai.GatewaySettingsState
import com.azizjon.network.ai.PersonResolver
import com.azizjon.network.ai.TargetChoiceState
import com.azizjon.network.backup.BackupStatus
import com.azizjon.network.backup.GitHubBackupConfig
import com.azizjon.network.data.CapabilityEntity
import com.azizjon.network.data.InteractionEntity
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.AiWriteProposal
import com.azizjon.network.data.PersonDraft
import com.azizjon.network.data.PersonEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

data class BackupUiState(
    val config: GitHubBackupConfig,
    val status: BackupStatus,
    val busy: Boolean = false,
)

class NetworkViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as NetworkApplication
    private val repository = app.repository
    private val settings = app.backupSettings
    private val backupManager = app.backupManager
    private val gatewaySettings = app.gatewaySettings
    private val gatewayClient = app.gatewayClient

    val snapshot: StateFlow<NetworkSnapshot> = repository.observeSnapshot().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NetworkSnapshot(),
    )

    private val _backupState = MutableStateFlow(BackupUiState(settings.config, settings.status))
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _aiDraft = MutableStateFlow("")
    val aiDraft: StateFlow<String> = _aiDraft.asStateFlow()

    private val _aiCaptureState = MutableStateFlow<AiCaptureState>(AiCaptureState.Idle)
    val aiCaptureState: StateFlow<AiCaptureState> = _aiCaptureState.asStateFlow()

    private val _aiSearchState = MutableStateFlow(AiSearchState())
    val aiSearchState: StateFlow<AiSearchState> = _aiSearchState.asStateFlow()

    private val _gatewaySettingsState = MutableStateFlow(gatewaySettings.state)
    val gatewaySettingsState: StateFlow<GatewaySettingsState> = _gatewaySettingsState.asStateFlow()

    private val _speechFallbackAllowed = MutableStateFlow(false)
    val speechFallbackAllowed: StateFlow<Boolean> = _speechFallbackAllowed.asStateFlow()

    private var backupJob: Job? = null
    private var captureJob: Job? = null
    private var searchJob: Job? = null

    init {
        if (settings.status.backupNeeded) scheduleAutoBackup()
    }

    fun showMessage(value: String) {
        _message.value = value
    }

    fun clearMessage() {
        _message.value = null
    }

    fun allowSpeechFallbackForSession() {
        _speechFallbackAllowed.value = true
    }

    fun savePerson(draft: PersonDraft, onSaved: (Long) -> Unit = {}) = mutate {
        val id = repository.savePerson(draft)
        onSaved(id)
    }

    fun deletePerson(person: PersonEntity, onDeleted: () -> Unit = {}) = mutate {
        repository.deletePerson(person)
        onDeleted()
    }

    fun addInteraction(personId: Long, note: String) = mutate {
        repository.addInteraction(personId, note)
    }

    fun addNeed(personId: Long, text: String) = mutate {
        repository.addNeed(personId, text)
    }

    fun addCapability(personId: Long, text: String) = mutate {
        repository.addCapability(personId, text)
    }

    fun deleteInteraction(item: InteractionEntity) = mutate { repository.deleteInteraction(item) }
    fun deleteNeed(item: NeedEntity) = mutate { repository.deleteNeed(item) }
    fun deleteCapability(item: CapabilityEntity) = mutate { repository.deleteCapability(item) }

    fun updateAiDraft(value: String) {
        if (_aiDraft.value == value) return
        captureJob?.cancel()
        _aiDraft.value = value
        _aiCaptureState.value = AiCaptureState.Idle
    }

    fun interpretAiDraft() {
        if (_aiCaptureState.value in setOf(AiCaptureState.Resolving, AiCaptureState.BuildingProposal, AiCaptureState.Applying)) return
        val rawInput = _aiDraft.value
        if (rawInput.isBlank()) {
            _aiCaptureState.value = AiCaptureState.Error("Write a note or update first.")
            return
        }
        if (rawInput.length > 4_000) {
            _aiCaptureState.value = AiCaptureState.Error("Keep the note under 4000 characters.")
            return
        }
        if (!gatewayClient.configured) {
            _aiCaptureState.value = AiCaptureState.Error("Add an access token in Settings first.")
            return
        }
        captureJob?.cancel()
        captureJob = viewModelScope.launch {
            _aiCaptureState.value = AiCaptureState.Resolving
            try {
                val now = Instant.now()
                val resolution = gatewayClient.resolveTarget(
                    input = rawInput,
                    now = now,
                    zoneId = ZoneId.systemDefault(),
                    locale = Locale.getDefault().toLanguageTag(),
                )
                if (_aiDraft.value != rawInput) return@launch
                val candidates = PersonResolver.resolve(snapshot.value.people, resolution.targetName)
                when {
                    candidates.exact != null -> buildAiProposal(rawInput, resolution.targetName, candidates.exact)
                    candidates.suggestions.isNotEmpty() -> {
                        _aiCaptureState.value = AiCaptureState.ChooseTarget(
                            TargetChoiceState(rawInput, resolution.targetName, candidates.suggestions),
                        )
                    }
                    else -> buildAiProposal(rawInput, resolution.targetName, null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (_aiDraft.value == rawInput) _aiCaptureState.value = AiCaptureState.Error(aiFailureMessage(error))
            }
        }
    }

    fun chooseAiTarget(personId: Long?) {
        val choice = (_aiCaptureState.value as? AiCaptureState.ChooseTarget)?.value ?: return
        val person = personId?.let(snapshot.value::person)
        if (personId != null && (person == null || person.archived)) {
            _aiCaptureState.value = AiCaptureState.Error("That person is no longer available.")
            return
        }
        captureJob?.cancel()
        captureJob = viewModelScope.launch {
            try {
                buildAiProposal(choice.rawInput, choice.targetName, person)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (_aiDraft.value == choice.rawInput) _aiCaptureState.value = AiCaptureState.Error(aiFailureMessage(error))
            }
        }
    }

    fun changeAiProposalTarget() {
        val proposal = (_aiCaptureState.value as? AiCaptureState.Preview)?.proposal ?: return
        val activePeople = snapshot.value.people.filterNot { it.archived }.sortedBy { it.name.lowercase() }
        _aiCaptureState.value = AiCaptureState.ChooseTarget(
            TargetChoiceState(proposal.rawInput, proposal.targetName, activePeople),
        )
    }

    fun updateAiProposal(proposal: AiWriteProposal) {
        val current = (_aiCaptureState.value as? AiCaptureState.Preview)?.proposal ?: return
        if (proposal.rawInput == current.rawInput && proposal.targetPersonId == current.targetPersonId) {
            _aiCaptureState.value = AiCaptureState.Preview(proposal)
        }
    }

    fun cancelAiCapture() {
        captureJob?.cancel()
        _aiCaptureState.value = AiCaptureState.Idle
    }

    fun applyAiProposal(onSaved: (Long) -> Unit = {}) {
        val proposal = (_aiCaptureState.value as? AiCaptureState.Preview)?.proposal ?: return
        captureJob?.cancel()
        captureJob = viewModelScope.launch {
            settings.markBackupNeeded()
            refreshBackupState()
            _aiCaptureState.value = AiCaptureState.Applying
            try {
                val result = repository.applyAiProposal(proposal)
                _aiDraft.value = ""
                _aiCaptureState.value = AiCaptureState.Idle
                scheduleAutoBackup()
                showMessage("Reviewed changes saved for ${proposal.targetName}")
                onSaved(result.personId)
            } catch (cancelled: CancellationException) {
                _aiCaptureState.value = AiCaptureState.Preview(proposal)
                throw cancelled
            } catch (error: Exception) {
                _aiCaptureState.value = AiCaptureState.Preview(proposal)
                showMessage(error.message ?: "The reviewed changes could not be saved. Nothing was changed.")
            }
        }
    }

    fun saveAccessToken(value: String) {
        try {
            gatewaySettings.saveToken(value)
            refreshGatewaySettingsState()
            showMessage("Access token saved. It will be verified on the next request.")
        } catch (error: Exception) {
            showMessage(error.message ?: "Could not save the access token")
        }
    }

    fun clearAccessToken() {
        gatewaySettings.clearToken()
        captureJob?.cancel()
        searchJob?.cancel()
        _aiCaptureState.value = AiCaptureState.Idle
        _aiSearchState.value = AiSearchState(message = "Access token removed. Local search is still available.")
        refreshGatewaySettingsState()
        showMessage("Access token removed")
    }

    fun acceptAiSearchConsent() {
        gatewaySettings.setFullNetworkSearchConsent(true)
        refreshGatewaySettingsState()
    }

    fun revokeAiSearchConsent() {
        gatewaySettings.setFullNetworkSearchConsent(false)
        searchJob?.cancel()
        _aiSearchState.value = AiSearchState(message = "Full-network AI search consent revoked. Local search is still available.")
        refreshGatewaySettingsState()
        showMessage("Full-network AI search consent revoked")
    }

    fun searchWithAi(query: String) {
        if (!_gatewaySettingsState.value.fullNetworkSearchConsent) {
            _aiSearchState.value = AiSearchState(query = query, message = "Accept the full-network search disclosure first.")
            return
        }
        if (!gatewayClient.configured) {
            _aiSearchState.value = AiSearchState(query = query, message = "Add an access token in Settings. Local matches are shown below.")
            return
        }
        if (query.isBlank() || query.length > 4_000) {
            _aiSearchState.value = AiSearchState(
                query = query,
                message = if (query.isBlank()) "Ask a network question first." else "Keep the question under 4000 characters.",
            )
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _aiSearchState.value = AiSearchState(query = query, loading = true)
            try {
                val results = gatewayClient.search(query, snapshot.value)
                _aiSearchState.value = AiSearchState(
                    query = query,
                    results = results,
                    message = if (results.isEmpty()) "The assistant found no evidence-backed matches." else "The assistant used the active searchable network shown in the disclosure.",
                    usedAi = true,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _aiSearchState.value = AiSearchState(
                    query = query,
                    message = "${aiFailureMessage(error)} Local matches are shown below.",
                )
            }
        }
    }

    fun saveBackupConfig(config: GitHubBackupConfig) {
        settings.save(config)
        refreshBackupState()
        showMessage("Backup settings saved")
        if (config.autoBackup && settings.status.backupNeeded) scheduleAutoBackup()
    }

    fun backupNow() {
        backupJob?.cancel()
        backupJob = viewModelScope.launch { performBackup(showSuccess = true) }
    }

    fun restoreFromGitHub(onRestored: () -> Unit = {}) {
        if (_backupState.value.busy) return
        viewModelScope.launch {
            val config = settings.config
            if (!config.configured) {
                showMessage("Complete and save the GitHub backup settings first")
                return@launch
            }
            setBackupBusy(true)
            settings.markAttemptStarted()
            refreshBackupState()
            try {
                val restored = backupManager.download(config)
                settings.markBackupNeeded()
                repository.replaceAll(restored)
                settings.markBackedUp()
                refreshBackupState()
                showMessage("Restored ${restored.people.size} people from encrypted backup")
                onRestored()
            } catch (e: Exception) {
                val safeMessage = e.message ?: "Restore failed"
                settings.markFailed(safeMessage)
                refreshBackupState()
                showMessage(safeMessage)
            } finally {
                setBackupBusy(false)
            }
        }
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            settings.markBackupNeeded()
            refreshBackupState()
            try {
                block()
                scheduleAutoBackup()
            } catch (e: Exception) {
                showMessage(e.message ?: "Could not save the change")
            }
        }
    }

    private fun scheduleAutoBackup() {
        val config = settings.config
        if (!config.autoBackup || !config.configured) return
        backupJob?.cancel()
        backupJob = viewModelScope.launch {
            delay(AUTO_BACKUP_DELAY_MS)
            performBackup(showSuccess = false)
        }
    }

    private suspend fun performBackup(showSuccess: Boolean) {
        if (_backupState.value.busy) return
        val config = settings.config
        if (!config.configured) {
            if (showSuccess) showMessage("Complete and save the GitHub backup settings first")
            return
        }
        setBackupBusy(true)
        settings.markAttemptStarted()
        refreshBackupState()
        try {
            val result = backupManager.backup(config)
            refreshBackupState()
            if (showSuccess) showMessage("Encrypted backup saved for ${result.people} people")
        } catch (e: Exception) {
            val safeMessage = e.message ?: "GitHub backup failed"
            settings.markFailed(safeMessage)
            refreshBackupState()
            showMessage(safeMessage)
        } finally {
            setBackupBusy(false)
        }
    }

    private fun setBackupBusy(busy: Boolean) {
        _backupState.value = _backupState.value.copy(busy = busy)
    }

    private fun refreshBackupState() {
        _backupState.value = _backupState.value.copy(config = settings.config, status = settings.status)
    }

    private suspend fun buildAiProposal(rawInput: String, targetName: String, person: PersonEntity?) {
        _aiCaptureState.value = AiCaptureState.BuildingProposal
        val proposal = gatewayClient.proposeChanges(
            input = rawInput,
            targetName = person?.name ?: targetName,
            snapshot = snapshot.value,
            person = person,
            now = Instant.now(),
            zoneId = ZoneId.systemDefault(),
            locale = Locale.getDefault().toLanguageTag(),
        )
        if (_aiDraft.value == rawInput) _aiCaptureState.value = AiCaptureState.Preview(proposal)
    }

    private fun refreshGatewaySettingsState() {
        _gatewaySettingsState.value = gatewaySettings.state
    }

    private fun aiFailureMessage(error: Throwable): String = when (error) {
        is GatewayException -> error.message ?: "The assistant could not complete the request."
        else -> error.message ?: "The assistant could not complete the request."
    }

    companion object {
        private const val AUTO_BACKUP_DELAY_MS = 1_500L
    }
}
