package com.azizjon.network.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.azizjon.network.NetworkApplication
import com.azizjon.network.ai.ActionState
import com.azizjon.network.ai.AgentClient
import com.azizjon.network.ai.AiRequestService
import com.azizjon.network.ai.AssistantAgent
import com.azizjon.network.ai.AssistantPrompt
import com.azizjon.network.ai.AssistantTools
import com.azizjon.network.ai.AttachedPhoto
import com.azizjon.network.ai.ChatAttachment
import com.azizjon.network.ai.ChatMessage
import com.azizjon.network.ai.ChatPhase
import com.azizjon.network.ai.ChatRole
import com.azizjon.network.ai.ChatState
import com.azizjon.network.ai.GatewaySettingsState
import com.azizjon.network.ai.PendingAction
import com.azizjon.network.ai.PendingItem
import com.azizjon.network.ai.PhotoException
import com.azizjon.network.ai.PhotoLimits
import com.azizjon.network.ai.PhotoPreparer
import com.azizjon.network.backup.BackupStatus
import com.azizjon.network.backup.GitHubBackupConfig
import com.azizjon.network.checkin.ChatSources
import com.azizjon.network.checkin.CheckinEntity
import com.azizjon.network.checkin.CheckinList
import com.azizjon.network.checkin.CheckinRules
import com.azizjon.network.checkin.CheckinSettingsState
import com.azizjon.network.checkin.EveningCheckin
import com.azizjon.network.checkin.NewPersonItem
import com.azizjon.network.data.AffiliationEntity
import com.azizjon.network.data.AiFeedbackEntity
import com.azizjon.network.data.CapabilityEntity
import com.azizjon.network.data.FactEntity
import com.azizjon.network.data.InteractionEntity
import com.azizjon.network.data.MoveDestination
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonDraft
import com.azizjon.network.data.PersonEntity
import com.azizjon.network.data.UndoConflictException
import com.azizjon.network.feedback.AiFeedbackLabel
import com.azizjon.network.feedback.buildFeedback
import com.azizjon.network.feedback.installedAppVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupUiState(
    val config: GitHubBackupConfig,
    val status: BackupStatus,
    val busy: Boolean = false,
)

data class FeedbackUiState(val reports: List<AiFeedbackEntity> = emptyList())

class NetworkViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as NetworkApplication
    private val repository = app.repository
    private val settings = app.backupSettings
    private val backupManager = app.backupManager
    private val gatewaySettings = app.gatewaySettings
    private val assistantStore = app.assistantStore
    private val captureDraftStore = app.captureDraftStore
    private val agent = AssistantAgent(
        client = app.agentClient,
        tools = AssistantTools(
            store = assistantStore,
            // Marked before the write rather than after the turn, so a process
            // killed mid-turn still leaves the backup flagged as due.
            beforeWrite = { settings.markBackupNeeded() },
        ),
    )

    val snapshot: StateFlow<NetworkSnapshot> = repository.observeSnapshot().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NetworkSnapshot(),
    )

    private val _backupState = MutableStateFlow(BackupUiState(settings.config, settings.status))
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _composerDraft = MutableStateFlow("")
    val composerDraft: StateFlow<String> = _composerDraft.asStateFlow()

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat.asStateFlow()

    /** True while the one-time assistant disclosure is waiting on an answer. */
    private val _consentRequested = MutableStateFlow(false)
    val consentRequested: StateFlow<Boolean> = _consentRequested.asStateFlow()

    val feedbackState: StateFlow<FeedbackUiState> = repository.observeFeedback()
        .map(::FeedbackUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedbackUiState())

    /** The assistant message the report sheet is open for, if any. */
    private val _feedbackTarget = MutableStateFlow<ChatMessage?>(null)
    val feedbackTarget: StateFlow<ChatMessage?> = _feedbackTarget.asStateFlow()

    private val _gatewaySettingsState = MutableStateFlow(gatewaySettings.state)
    val gatewaySettingsState: StateFlow<GatewaySettingsState> = _gatewaySettingsState.asStateFlow()

    /** Photos attached to the unsent message. Memory only, like the thread. */
    private val _photos = MutableStateFlow<List<AttachedPhoto>>(emptyList())
    val photos: StateFlow<List<AttachedPhoto>> = _photos.asStateFlow()

    /** Photos still being shrunk; sending waits for them. */
    private val _preparingPhotos = MutableStateFlow(0)
    val preparingPhotos: StateFlow<Int> = _preparingPhotos.asStateFlow()

    /** A tab another part of the app asked to show, such as a shared photo opening the Assistant. */
    private val _requestedSection = MutableStateFlow<AppSection?>(null)
    val requestedSection: StateFlow<AppSection?> = _requestedSection.asStateFlow()

    private val photoPreparer = PhotoPreparer(app)
    private var nextPhotoId = 1L

    /** Set when something filled the composer for the user, so it opens ready to type. */
    private val _composerFocusRequested = MutableStateFlow(false)
    val composerFocusRequested: StateFlow<Boolean> = _composerFocusRequested.asStateFlow()

    private val checkins = app.checkins
    private val checkinSettingsStore = app.checkinSettings

    private val _checkinSettings = MutableStateFlow(checkinSettingsStore.state)
    val checkinSettings: StateFlow<CheckinSettingsState> = _checkinSettings.asStateFlow()

    /** Re-evaluated hourly as well, so a "later" comes back without waiting for a change. */
    val checkinList: StateFlow<CheckinList> = combine(
        checkins.observe(),
        snapshot,
        flow {
            while (true) {
                emit(Unit)
                delay(CHECKIN_REFRESH_MS)
            }
        },
    ) { rows, network, _ -> CheckinRules.build(rows, network, System.currentTimeMillis()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckinList())

    private var backupJob: Job? = null
    private var chatJob: Job? = null
    private var draftSaveJob: Job? = null
    private var nextMessageId = 1L

    init {
        if (settings.status.backupNeeded) scheduleAutoBackup()
        refreshCheckinSources()
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                runCatching { captureDraftStore.read() }.getOrDefault("")
            }
            // Never clobber something typed while the encrypted read was running.
            if (restored.isNotBlank() && _composerDraft.value.isBlank()) _composerDraft.value = restored
        }
    }

    fun showMessage(value: String) {
        _message.value = value
    }

    fun clearMessage() {
        _message.value = null
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

    fun addAffiliation(personId: Long, organization: String, role: String, education: Boolean) = mutate {
        repository.addAffiliation(personId, organization, role, education)
    }

    fun addFact(personId: Long, text: String) = mutate { repository.addFact(personId, text) }

    /** Moves a note filed against the wrong person onto the right one, from the person screen. */
    fun moveInteraction(
        interactionId: Long,
        destination: MoveDestination,
        onMoved: (Long) -> Unit = {},
    ) = mutate {
        val result = repository.moveInteraction(interactionId, destination)
        showMessage("Moved ${result.summary} to ${result.personName}")
        onMoved(result.personId)
    }

    fun deleteFact(item: FactEntity) = mutate { repository.deleteFact(item) }

    fun setAffiliationCurrent(item: AffiliationEntity, current: Boolean) = mutate {
        repository.setAffiliationCurrent(item, current)
    }

    fun deleteAffiliation(item: AffiliationEntity) = mutate { repository.deleteAffiliation(item) }

    fun deleteInteraction(item: InteractionEntity) = mutate { repository.deleteInteraction(item) }
    fun deleteNeed(item: NeedEntity) = mutate { repository.deleteNeed(item) }
    fun deleteCapability(item: CapabilityEntity) = mutate { repository.deleteCapability(item) }

    // Each tap takes the card off the list being looked at, so say where it went.
    fun setNeedHelped(item: NeedEntity, helped: Boolean) = mutate {
        repository.setNeedHelped(item, helped)
        showMessage(if (helped) "Moved to Helped" else "Moved back to To help")
    }

    fun setNeedActive(item: NeedEntity, active: Boolean) = mutate {
        repository.setNeedActive(item, active)
        showMessage(if (active) "Reopened" else "Moved to Closed")
    }

    fun updateComposerDraft(value: String) {
        if (_composerDraft.value == value) return
        _composerDraft.value = value
        persistDraft(value)
    }

    /**
     * Shrinks and attaches photos to the unsent message, up to the limit.
     *
     * [fromCamera] marks a capture this app owns, which is deleted once read so
     * a photo of someone's card does not linger in the cache.
     */
    fun attachPhotos(uris: List<Uri>, fromCamera: Boolean = false) {
        if (uris.isEmpty()) return
        val room = PhotoLimits.MAX_PHOTOS - _photos.value.size - _preparingPhotos.value
        val accepted = uris.take(room.coerceAtLeast(0))
        if (accepted.size < uris.size) {
            showMessage("A message can carry ${PhotoLimits.MAX_PHOTOS} photos, so not all of them were added.")
        }
        if (fromCamera) uris.drop(accepted.size).forEach(photoPreparer::deleteCapture)
        accepted.forEach { uri ->
            _preparingPhotos.value += 1
            viewModelScope.launch {
                try {
                    val photo = photoPreparer.prepare(uri, nextPhotoId++)
                    _photos.value = (_photos.value + photo).take(PhotoLimits.MAX_PHOTOS)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: PhotoException) {
                    showMessage(error.message ?: UNREADABLE_PHOTO)
                } catch (error: Exception) {
                    // Decoder messages are technical; the user only needs to know it failed.
                    showMessage(UNREADABLE_PHOTO)
                } finally {
                    _preparingPhotos.value -= 1
                    if (fromCamera) photoPreparer.deleteCapture(uri)
                }
            }
        }
    }

    fun removePhoto(id: Long) {
        _photos.value = _photos.value.filterNot { it.id == id }
    }

    /** Where the camera app should write a photo for the composer. */
    fun newCaptureUri(): Uri = photoPreparer.newCaptureUri()

    /**
     * Takes what another app shared: photos join the composer, text is added
     * to it, and the Assistant tab opens. Nothing is sent until the user does.
     */
    fun receiveShare(text: String?, photos: List<Uri>) {
        text?.trim()?.takeIf(String::isNotEmpty)?.let { shared ->
            val current = _composerDraft.value.trimEnd()
            val combined = if (current.isEmpty()) shared else "$current\n$shared"
            updateComposerDraft(combined.take(AgentClient.MAX_INPUT_CHARACTERS))
        }
        attachPhotos(photos)
        openSection(AppSection.CHAT)
    }

    fun openSection(section: AppSection) {
        _requestedSection.value = section
    }

    fun clearRequestedSection() {
        _requestedSection.value = null
    }

    fun composerFocused() {
        _composerFocusRequested.value = false
    }

    /**
     * Starts a message about someone from the check-in. The user finishes and
     * sends it; the assistant then files it like anything else they tell it.
     */
    private fun prefillAssistant(text: String) {
        val current = _composerDraft.value.trimEnd()
        updateComposerDraft((if (current.isEmpty()) text else "$current\n$text").take(AgentClient.MAX_INPUT_CHARACTERS))
        _composerFocusRequested.value = true
        openSection(AppSection.CHAT)
    }

    fun checkinAddPerson(item: NewPersonItem) {
        val where = when (val source = item.sources.first()) {
            CheckinEntity.SOURCE_CONTACTS -> "saved in my phone"
            else -> "wrote to me on ${ChatSources.label(source)}"
        }
        prefillAssistant("${item.name} ($where): ")
        setCheckinStatus(item.refs, CheckinEntity.STATUS_DONE)
    }

    fun checkinAddNote(name: String, refs: List<String>) {
        prefillAssistant("About $name: ")
        if (refs.isNotEmpty()) setCheckinStatus(refs, CheckinEntity.STATUS_DONE)
    }

    fun setCheckinStatus(refs: List<String>, status: String) {
        viewModelScope.launch {
            try {
                checkins.setStatus(refs, status)
            } catch (error: Exception) {
                showMessage(error.message ?: "Could not save that answer")
            }
        }
    }

    fun skipAllNewPeople() {
        val people = checkinList.value.newPeople
        setCheckinStatus(people.flatMap { it.refs }, CheckinEntity.STATUS_NEVER)
        showMessage("Skipped ${people.size} people")
    }

    fun setRecordCheckinStatus(ref: String, status: String) {
        viewModelScope.launch {
            try {
                checkins.setRecordStatus(ref, status)
            } catch (error: Exception) {
                showMessage(error.message ?: "Could not save that answer")
            }
        }
    }

    // Record answers change the network, so they go through mutate() and the backup marker.
    fun confirmNeedStillOpen(item: NeedEntity) = mutate { repository.confirmNeed(item) }

    fun closeNeedFromCheckin(item: NeedEntity) = mutate {
        repository.setNeedActive(item, false)
        showMessage("Moved to Closed on the Needs tab")
    }

    fun confirmPosition(item: AffiliationEntity) = mutate { repository.confirmAffiliation(item) }

    fun endPosition(item: AffiliationEntity) = mutate {
        repository.endAffiliation(item)
        showMessage("Marked as a past position. Tell the assistant where they are now.")
    }

    fun setEveningReminder(enabled: Boolean, minutes: Int) {
        checkinSettingsStore.setEvening(enabled, minutes)
        refreshCheckinSettings()
        EveningCheckin.schedule(app, checkinSettingsStore.state, replace = true)
    }

    fun setContactsSource(enabled: Boolean) {
        checkinSettingsStore.setContacts(enabled)
        refreshCheckinSettings()
        if (enabled) refreshCheckinSources()
    }

    fun setChatsSource(enabled: Boolean) {
        checkinSettingsStore.setChats(enabled)
        refreshCheckinSettings()
    }

    /** Picks up contacts saved since the last look. Cheap: names only. */
    fun refreshCheckinSources() {
        if (!checkinSettingsStore.state.contactsEnabled) return
        viewModelScope.launch {
            try {
                checkins.refreshContacts()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The address book was unreadable this time; the next look will try again.
            }
        }
    }

    private fun refreshCheckinSettings() {
        _checkinSettings.value = checkinSettingsStore.state
    }

    /**
     * Sends the composer text to the assistant as the next turn in the thread.
     *
     * The first message waits on the one-time disclosure: from here on the
     * assistant reads whichever records it decides it needs, and the user should
     * agree to that once before it happens.
     */
    fun sendChatMessage() {
        val text = _composerDraft.value.trim()
        val photos = _photos.value
        if (_chat.value.phase.busy || (text.isEmpty() && photos.isEmpty())) return
        if (_preparingPhotos.value > 0) {
            showMessage("Still getting the photos ready. Try again in a moment.")
            return
        }
        if (text.length > AgentClient.MAX_INPUT_CHARACTERS) {
            showMessage("That message is too long. Keep it under ${AgentClient.MAX_INPUT_CHARACTERS} characters.")
            return
        }
        if (!agent.configured) {
            showMessage("Add an access token in Settings first.")
            return
        }
        if (!_gatewaySettingsState.value.assistantConsent) {
            _consentRequested.value = true
            return
        }
        val history = AssistantPrompt.history(_chat.value.messages)
        appendUser(text, photos)
        _composerDraft.value = ""
        _photos.value = emptyList()
        viewModelScope.launch { clearDraft() }

        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            setPhase(ChatPhase.Working(if (photos.isEmpty()) "Reading your message…" else "Reading your photos…"))
            try {
                val outcome = AiRequestService.holdingProcess(app) {
                    agent.run(text, photos.map { it.jpeg }, history) { status -> setPhase(ChatPhase.Working(status)) }
                }
                val log = outcome.log
                if (log.changes.isNotEmpty()) {
                    refreshBackupState()
                    scheduleAutoBackup()
                }
                // A lookup that saved nothing still gets a card: what was sent to
                // the web is worth seeing even when the answer was "not sure".
                val attachment = if (log.saved.isEmpty() && log.pending.isEmpty() && log.web.isEmpty()) {
                    null
                } else {
                    ChatAttachment.AgentResult(
                        saved = log.saved.toList(),
                        changes = log.changes.toList(),
                        memory = log.memory.toList(),
                        pending = log.pending.map { PendingItem(it) },
                        people = log.people.toList(),
                        calls = log.calls.toList(),
                        web = log.web.map { it.line },
                    )
                }
                if (outcome.error == null) {
                    appendAssistant(outcome.reply.orEmpty(), attachment, fromGateway = true)
                } else {
                    appendAssistant(outcome.error, failed = true, fromGateway = true)
                    // Whatever landed before the failure is still saved, so it
                    // still gets its card and its undo.
                    if (attachment != null) appendAssistant("This was saved before it stopped.", attachment)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appendAssistant(error.message ?: "The assistant could not complete the request.", failed = true, fromGateway = true)
            } finally {
                setPhase(ChatPhase.Idle)
            }
        }
    }

    fun confirmAssistantConsent() {
        gatewaySettings.setAssistantConsent(true)
        refreshGatewaySettingsState()
        _consentRequested.value = false
        sendChatMessage()
    }

    fun dismissAssistantConsent() {
        _consentRequested.value = false
        showMessage("The assistant needs that permission. Browsing and local search on the People tab still work.")
    }

    /**
     * Puts back everything one reply saved, all or nothing.
     *
     * Refuses when something was edited since, rather than overwriting the newer
     * version; the card then says so and stops offering undo.
     */
    fun undoChatChanges(messageId: Long) {
        val result = agentResult(messageId)?.takeIf { it.canUndo } ?: return
        if (_chat.value.phase.busy) return
        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            setPhase(ChatPhase.Undoing)
            settings.markBackupNeeded()
            refreshBackupState()
            try {
                assistantStore.undo(result.changes, System.currentTimeMillis())
                updateAgentResult(messageId) { it.copy(undone = true) }
                appendAssistant("Undone. Everything that reply saved is back the way it was.")
                scheduleAutoBackup()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (conflict: UndoConflictException) {
                updateAgentResult(messageId) { it.copy(undoError = conflict.message) }
            } catch (error: Exception) {
                updateAgentResult(messageId) { it.copy(undoError = error.message ?: "Undo failed. Nothing was changed.") }
            } finally {
                setPhase(ChatPhase.Idle)
            }
        }
    }

    /** Carries out a delete or merge the user just confirmed on the card. */
    fun confirmChatAction(messageId: Long, actionId: String) {
        val item = agentResult(messageId)?.pending?.firstOrNull { it.action.id == actionId && it.state == ActionState.PENDING } ?: return
        if (_chat.value.phase.busy) return
        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            setPhase(ChatPhase.Confirming)
            settings.markBackupNeeded()
            refreshBackupState()
            try {
                val outcome = when (val action = item.action) {
                    is PendingAction.DeletePerson -> "Deleted ${assistantStore.deletePerson(action.personId)}."
                    is PendingAction.DeleteNote -> assistantStore.deleteNote(action.noteId).let { "Deleted the note." }
                    is PendingAction.DeleteRecord -> assistantStore.deleteRecord(action.ref).let { "Deleted." }
                    is PendingAction.MergePeople -> assistantStore.mergePeople(action.keepId, action.mergeId, System.currentTimeMillis())
                        .let { "Merged ${it.mergedName} into ${it.keptName}." }
                }
                setActionState(messageId, actionId, ActionState.DONE, outcome)
                scheduleAutoBackup()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                setActionState(messageId, actionId, ActionState.FAILED, error.message ?: "That could not be done. Nothing was changed.")
            } finally {
                setPhase(ChatPhase.Idle)
            }
        }
    }

    fun keepChatAction(messageId: Long, actionId: String) {
        setActionState(messageId, actionId, ActionState.KEPT, "Kept.")
    }

    fun startNewChat() {
        chatJob?.cancel()
        _chat.value = ChatState()
    }

    /**
     * Opens the report sheet for one assistant response.
     *
     * Only assistant turns can be reported, and only once: a second report on
     * the same message would duplicate the same evidence under a second label
     * without adding anything, so the thread stops offering it.
     */
    fun startFeedback(messageId: Long) {
        val message = _chat.value.messages.firstOrNull { it.id == messageId } ?: return
        if (message.role != ChatRole.ASSISTANT || message.reportedLabel != null) return
        _feedbackTarget.value = message
    }

    fun dismissFeedback() {
        _feedbackTarget.value = null
    }

    /**
     * Stores the report, copying the response into it as it goes.
     *
     * Nothing is sent as the report is saved. It sits in the local database and
     * leaves only inside the next encrypted backup, which the user starts.
     */
    fun submitFeedback(label: AiFeedbackLabel, note: String) {
        val message = _feedbackTarget.value ?: return
        _feedbackTarget.value = null
        viewModelScope.launch {
            try {
                repository.recordFeedback(
                    buildFeedback(
                        message = message,
                        userMessage = precedingUserMessage(message.id),
                        label = label,
                        note = note,
                        appVersion = installedAppVersion(app),
                    ),
                )
                updateMessage(message.id) { it.copy(reportedLabel = label.id) }
                showMessage("Reported. It travels with your next encrypted backup.")
            } catch (error: Exception) {
                showMessage(error.message ?: "Could not save the report")
            }
        }
    }

    fun deleteFeedback(id: Long) {
        viewModelScope.launch {
            try {
                repository.deleteFeedback(id)
                settings.markBackupNeeded()
                refreshBackupState()
                scheduleAutoBackup()
            } catch (error: Exception) {
                showMessage(error.message ?: "Could not delete the report")
            }
        }
    }

    fun clearAllFeedback() {
        viewModelScope.launch {
            try {
                repository.clearFeedback()
                // Deleting reports changes what the next backup should contain.
                settings.markBackupNeeded()
                refreshBackupState()
                scheduleAutoBackup()
                showMessage("All assistant reports deleted")
            } catch (error: Exception) {
                showMessage(error.message ?: "Could not delete the reports")
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
        chatJob?.cancel()
        setPhase(ChatPhase.Idle)
        refreshGatewaySettingsState()
        appendAssistant("Access token removed. Browsing and local search on the People tab still work.")
        showMessage("Access token removed")
    }

    fun revokeAssistantConsent() {
        gatewaySettings.setAssistantConsent(false)
        refreshGatewaySettingsState()
        showMessage("Assistant permission revoked")
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

    /**
     * Checks that the saved passphrase opens the stored backup, changing nothing.
     *
     * Deliberately separate from Restore. The question "can I still get my data
     * back" should be answerable without the destructive action that answers it
     * by accident, and a passphrase that has drifted out of sync stays invisible
     * until then.
     */
    fun verifyBackup() {
        if (_backupState.value.busy) return
        viewModelScope.launch {
            val config = settings.config
            if (!config.configured) {
                showMessage("Complete and save the GitHub backup settings first")
                return@launch
            }
            setBackupBusy(true)
            try {
                val check = backupManager.verify(config)
                val reports = if (check.feedback == 0) "" else ", ${check.feedback} report(s)"
                showMessage(
                    "Passphrase opens the stored backup: ${check.people} people, " +
                        "${check.interactions} notes$reports",
                )
            } catch (e: Exception) {
                // Left off the backup status on purpose: nothing was attempted,
                // so a failed check must not make a good backup look broken.
                showMessage(e.message ?: "The stored backup could not be checked")
            } finally {
                setBackupBusy(false)
            }
        }
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
                repository.replaceAll(restored.snapshot, restored.feedback)
                settings.markBackedUp()
                refreshBackupState()
                // Undo records point at rows the restore just replaced.
                startNewChat()
                showMessage("Restored ${restored.snapshot.people.size} people from encrypted backup")
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

    private fun agentResult(messageId: Long): ChatAttachment.AgentResult? =
        _chat.value.messages.firstOrNull { it.id == messageId }?.attachment as? ChatAttachment.AgentResult

    private fun updateAgentResult(messageId: Long, transform: (ChatAttachment.AgentResult) -> ChatAttachment.AgentResult) =
        updateMessage(messageId) { message ->
            val result = message.attachment as? ChatAttachment.AgentResult ?: return@updateMessage message
            message.copy(attachment = transform(result))
        }

    private fun setActionState(messageId: Long, actionId: String, state: ActionState, outcome: String) =
        updateAgentResult(messageId) { result ->
            result.copy(
                pending = result.pending.map { item ->
                    if (item.action.id == actionId) item.copy(state = state, outcome = outcome) else item
                },
            )
        }

    private fun appendUser(text: String, photos: List<AttachedPhoto>) =
        append(ChatRole.USER, text, null, false, photos = photos)

    private fun appendAssistant(
        text: String,
        attachment: ChatAttachment? = null,
        failed: Boolean = false,
        fromGateway: Boolean = false,
    ) = append(ChatRole.ASSISTANT, text, attachment, failed, fromGateway)

    private fun append(
        role: ChatRole,
        text: String,
        attachment: ChatAttachment?,
        failed: Boolean,
        fromGateway: Boolean = false,
        photos: List<AttachedPhoto> = emptyList(),
    ) {
        val message = ChatMessage(
            id = nextMessageId++,
            role = role,
            text = text,
            attachment = attachment,
            failed = failed,
            sentAt = System.currentTimeMillis(),
            fromGateway = fromGateway,
            photoCount = photos.size,
            thumbnails = photos.map(AttachedPhoto::thumbnail),
        )
        _chat.value = _chat.value.copy(messages = _chat.value.messages + message)
    }

    private fun updateMessage(messageId: Long, transform: (ChatMessage) -> ChatMessage) {
        _chat.value = _chat.value.copy(
            messages = _chat.value.messages.map { if (it.id == messageId) transform(it) else it },
        )
    }

    /** The turn that produced a response, so a report shows both halves. */
    private fun precedingUserMessage(messageId: Long): String =
        _chat.value.messages.lastOrNull { it.id < messageId && it.role == ChatRole.USER }
            ?.let { AssistantPrompt.userLine(it.text, it.photoCount) }
            .orEmpty()

    private fun setPhase(phase: ChatPhase) {
        _chat.value = _chat.value.copy(phase = phase)
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
            if (showSuccess) {
                val reports = if (result.feedback == 0) "" else " and ${result.feedback} assistant report(s)"
                showMessage("Encrypted backup saved for ${result.people} people$reports")
            }
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

    /**
     * Writes the unsent note to disk shortly after typing stops.
     *
     * Debounced because each write encrypts the whole draft, and a keystroke is
     * not worth that. Losing the last few hundred milliseconds of a note to a
     * process death is a far smaller loss than the whole note, which is what
     * happened before anything was persisted at all.
     */
    private fun persistDraft(value: String) {
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(DRAFT_SAVE_DELAY_MS)
            withContext(Dispatchers.IO) { runCatching { captureDraftStore.write(value) } }
        }
    }

    private suspend fun clearDraft() {
        draftSaveJob?.cancel()
        withContext(Dispatchers.IO) { runCatching { captureDraftStore.clear() } }
    }

    private fun refreshGatewaySettingsState() {
        _gatewaySettingsState.value = gatewaySettings.state
    }

    companion object {
        private const val AUTO_BACKUP_DELAY_MS = 1_500L
        private const val DRAFT_SAVE_DELAY_MS = 400L
        private const val UNREADABLE_PHOTO = "That photo could not be opened."
        private const val CHECKIN_REFRESH_MS = 60 * 60 * 1000L
    }
}
