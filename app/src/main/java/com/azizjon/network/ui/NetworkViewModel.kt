package com.azizjon.network.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.azizjon.network.NetworkApplication
import com.azizjon.network.ai.AiRequestService
import com.azizjon.network.ai.ChatAttachment
import com.azizjon.network.ai.ChatIntent
import com.azizjon.network.ai.ChatMessage
import com.azizjon.network.ai.ChatPhase
import com.azizjon.network.ai.ChatRole
import com.azizjon.network.ai.ChatRouter
import com.azizjon.network.ai.ChatState
import com.azizjon.network.ai.GatewayClient
import com.azizjon.network.ai.GatewayException
import com.azizjon.network.ai.GatewaySettingsState
import com.azizjon.network.ai.PersonResolver
import com.azizjon.network.ai.TargetChoiceState
import com.azizjon.network.backup.BackupStatus
import com.azizjon.network.backup.GitHubBackupConfig
import com.azizjon.network.data.AffiliationEntity
import com.azizjon.network.data.CapabilityEntity
import com.azizjon.network.data.FactEntity
import com.azizjon.network.data.InteractionEntity
import com.azizjon.network.data.MoveDestination
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.AiWriteProposal
import com.azizjon.network.data.PersonDraft
import com.azizjon.network.data.AiFeedbackEntity
import com.azizjon.network.data.PersonEntity
import com.azizjon.network.feedback.AiFeedbackLabel
import com.azizjon.network.feedback.buildFeedback
import com.azizjon.network.feedback.installedAppVersion
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

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
    private val gatewayClient = app.gatewayClient
    private val captureDraftStore = app.captureDraftStore

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

    /** The question waiting on the one-time full-network disclosure, if any. */
    private val _searchConsentRequest = MutableStateFlow<String?>(null)
    val searchConsentRequest: StateFlow<String?> = _searchConsentRequest.asStateFlow()

    val feedbackState: StateFlow<FeedbackUiState> = repository.observeFeedback()
        .map(::FeedbackUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedbackUiState())

    /** The assistant message the report sheet is open for, if any. */
    private val _feedbackTarget = MutableStateFlow<ChatMessage?>(null)
    val feedbackTarget: StateFlow<ChatMessage?> = _feedbackTarget.asStateFlow()

    private val _gatewaySettingsState = MutableStateFlow(gatewaySettings.state)
    val gatewaySettingsState: StateFlow<GatewaySettingsState> = _gatewaySettingsState.asStateFlow()

    private val _speechFallbackAllowed = MutableStateFlow(false)
    val speechFallbackAllowed: StateFlow<Boolean> = _speechFallbackAllowed.asStateFlow()

    private var backupJob: Job? = null
    private var chatJob: Job? = null
    private var draftSaveJob: Job? = null
    private var nextMessageId = 1L
    private var pendingSearch: PendingSearch? = null

    init {
        if (settings.status.backupNeeded) scheduleAutoBackup()
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

    fun addAffiliation(personId: Long, organization: String, role: String, education: Boolean) = mutate {
        repository.addAffiliation(personId, organization, role, education)
    }

    fun addFact(personId: Long, text: String) = mutate { repository.addFact(personId, text) }

    /**
     * Moves a note filed against the wrong person onto the right one.
     *
     * The assistant cannot do this itself - it is scoped to one person per
     * request, and moving records between people is exactly the kind of
     * cross-person write that boundary exists to prevent. So the app performs
     * it directly, from an explicit choice the user just made.
     */
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

    fun updateComposerDraft(value: String) {
        if (_composerDraft.value == value) return
        _composerDraft.value = value
        persistDraft(value)
    }

    /**
     * Sends the composer text as the next turn in the thread.
     *
     * While a proposal is open the turn refines it instead of starting a new
     * capture. That is what makes a correction cheap: the pending proposal goes
     * back with the correction, so the assistant revises it rather than
     * re-deriving everything from the original note.
     */
    fun sendChatMessage() {
        val text = _composerDraft.value.trim()
        if (_chat.value.phase.busy || text.isEmpty()) return
        val history = _chat.value.messages
        val pending = _chat.value.pendingProposal
        // Typing instead of picking abandons an open person picker. Drop the card
        // so a later tap cannot resurrect the message it was asked about.
        _chat.value.pendingTargetChoice?.let { (messageId, _) -> clearAttachment(messageId) }
        appendUser(text)
        _composerDraft.value = ""
        viewModelScope.launch { clearDraft() }

        if (text.length > GatewayClient.MAX_INPUT_CHARACTERS) {
            appendAssistant(
                "That message is too long. Keep it under " + "${GatewayClient.MAX_INPUT_CHARACTERS} characters.",
                failed = true,
            )
            return
        }
        if (!gatewayClient.configured) {
            appendAssistant("Add an access token in Settings first.", failed = true)
            return
        }
        launchChat {
            if (pending != null) {
                refineProposal(pending.first, pending.second, text, history)
            } else {
                routeMessage(text, history)
            }
        }
    }

    fun chooseChatTarget(personId: Long?) {
        val (messageId, choice) = _chat.value.pendingTargetChoice ?: return
        if (_chat.value.phase.busy) return
        val person = personId?.let(snapshot.value::person)
        if (personId != null && (person == null || person.archived)) {
            appendAssistant("That person is no longer available.", failed = true)
            return
        }
        val history = messagesBefore(messageId)
        clearAttachment(messageId)
        launchChat { buildProposal(choice.rawInput, choice.targetName, person, history) }
    }

    /** Reopens the person picker for the proposal still under review. */
    fun changeChatProposalTarget() {
        val (messageId, proposal) = _chat.value.pendingProposal ?: return
        if (_chat.value.phase.busy) return
        val activePeople = snapshot.value.people.filterNot { it.archived }.sortedBy { it.name.lowercase() }
        updateMessage(messageId) {
            it.copy(
                attachment = ChatAttachment.TargetChoice(
                    TargetChoiceState(proposal.rawInput, proposal.targetName, activePeople),
                ),
            )
        }
    }

    fun updateChatProposal(proposal: AiWriteProposal) {
        val (messageId, current) = _chat.value.pendingProposal ?: return
        // Hand edits may only change the contents. Re-pointing a proposal at
        // somebody else goes through changeChatProposalTarget, which rebuilds it.
        if (proposal.rawInput != current.rawInput || proposal.targetPersonId != current.targetPersonId) return
        updateMessage(messageId) { message ->
            val existing = message.attachment as? ChatAttachment.Proposal
            message.copy(attachment = ChatAttachment.Proposal(proposal, caveat = existing?.caveat))
        }
    }

    fun discardChatProposal() {
        val (messageId, _) = _chat.value.pendingProposal ?: return
        chatJob?.cancel()
        clearAttachment(messageId)
        appendAssistant("Discarded. Nothing was saved.")
        setPhase(ChatPhase.Idle)
    }

    fun applyChatProposal() {
        val (messageId, proposal) = _chat.value.pendingProposal ?: return
        if (_chat.value.phase.busy) return
        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            settings.markBackupNeeded()
            refreshBackupState()
            setPhase(ChatPhase.Applying)
            try {
                val result = repository.applyAiProposal(proposal)
                updateMessage(messageId) { message ->
                    val existing = message.attachment as? ChatAttachment.Proposal
                    message.copy(
                        attachment = ChatAttachment.Proposal(
                            proposal = proposal,
                            savedPersonId = result.personId,
                            caveat = existing?.caveat,
                            savedInteractionId = result.auditInteractionId,
                        ),
                    )
                }
                appendAssistant("Saved for ${proposal.targetName}.")
                scheduleAutoBackup()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appendAssistant(
                    error.message ?: "The reviewed changes could not be saved. Nothing was changed.",
                    failed = true,
                )
            } finally {
                setPhase(ChatPhase.Idle)
            }
        }
    }

    fun confirmSearchConsent() {
        val request = pendingSearch ?: return
        _searchConsentRequest.value = null
        pendingSearch = null
        gatewaySettings.setFullNetworkSearchConsent(true)
        refreshGatewaySettingsState()
        launchChat { runSearch(request.query, request.history) }
    }

    fun dismissSearchConsent() {
        _searchConsentRequest.value = null
        pendingSearch = null
        appendAssistant(
            "Full-network search needs that one-time disclosure. Browsing and local matching on the People tab still work without it.",
        )
    }

    fun startNewChat() {
        chatJob?.cancel()
        pendingSearch = null
        _searchConsentRequest.value = null
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
        appendAssistant("Access token removed. Browsing and local matching on the People tab still work.")
        showMessage("Access token removed")
    }

    fun revokeAiSearchConsent() {
        gatewaySettings.setFullNetworkSearchConsent(false)
        pendingSearch = null
        _searchConsentRequest.value = null
        refreshGatewaySettingsState()
        showMessage("Full-network AI search consent revoked")
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
                repository.replaceAll(restored.snapshot, restored.feedback)
                settings.markBackedUp()
                refreshBackupState()
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

    private fun launchChat(block: suspend () -> Unit) {
        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            try {
                AiRequestService.holdingProcess(app) { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appendAssistant(aiFailureMessage(error), failed = true, fromGateway = true)
            } finally {
                setPhase(ChatPhase.Idle)
            }
        }
    }

    /**
     * Decides whether a new turn stores something or asks something, then runs it.
     *
     * The routing call is the same round trip that already named the target
     * person, so a capture pays nothing for it. History is passed at the narrower
     * capture scope because at this point the turn could still be either kind.
     */
    private suspend fun routeMessage(text: String, history: List<ChatMessage>) {
        // A note that plainly names one saved person, and does not read as a
        // question, is resolved on the phone and skips the routing round trip.
        if (!ChatRouter.looksLikeQuestion(text)) {
            val known = PersonResolver.resolveFromNote(snapshot.value.people, text)
            if (known != null) {
                buildProposal(text, known.name, known, history)
                return
            }
        }
        setPhase(ChatPhase.Routing)
        val resolution = gatewayClient.resolveTarget(
            input = text,
            history = ChatRouter.captureScopedTurns(history),
            now = Instant.now(),
            zoneId = ZoneId.systemDefault(),
            locale = Locale.getDefault().toLanguageTag(),
        )
        if (resolution.intent == ChatIntent.SEARCH) {
            runSearch(text, history)
            return
        }
        val candidates = PersonResolver.resolve(snapshot.value.people, resolution.targetName)
        when {
            candidates.exact != null -> buildProposal(text, resolution.targetName, candidates.exact, history)
            candidates.suggestions.isNotEmpty() -> appendAssistant(
                "Which " + "${resolution.targetName} do you mean?",
                ChatAttachment.TargetChoice(TargetChoiceState(text, resolution.targetName, candidates.suggestions)),
                fromGateway = true,
            )
            else -> buildProposal(text, resolution.targetName, null, history)
        }
    }

    private suspend fun runSearch(query: String, history: List<ChatMessage>) {
        if (!_gatewaySettingsState.value.fullNetworkSearchConsent) {
            pendingSearch = PendingSearch(query, history)
            _searchConsentRequest.value = query
            return
        }
        setPhase(ChatPhase.Searching)
        // Search already sends every active person in the same request, so
        // replaying earlier turns here discloses nothing new.
        val reply = gatewayClient.search(query, snapshot.value, ChatRouter.searchScopedTurns(history))
        appendAssistant(reply.assistantMessage, ChatAttachment.Search(reply.results), fromGateway = true)
    }

    private suspend fun buildProposal(
        input: String,
        targetName: String,
        person: PersonEntity?,
        history: List<ChatMessage>,
    ) {
        setPhase(ChatPhase.Capturing)
        val reply = gatewayClient.proposeChanges(
            input = input,
            targetName = person?.name ?: targetName,
            snapshot = snapshot.value,
            person = person,
            now = Instant.now(),
            zoneId = ZoneId.systemDefault(),
            locale = Locale.getDefault().toLanguageTag(),
            history = ChatRouter.captureScopedTurns(history),
        )
        appendAssistant(
            reply.assistantMessage,
            ChatAttachment.Proposal(reply.proposal, caveat = reply.caveat),
            fromGateway = true,
        )
    }

    /**
     * Revises the open proposal from a correction instead of starting over.
     *
     * The superseded card loses its attachment so only one proposal is ever open,
     * which keeps the apply action unambiguous.
     */
    private suspend fun refineProposal(
        messageId: Long,
        proposal: AiWriteProposal,
        correction: String,
        history: List<ChatMessage>,
    ) {
        setPhase(ChatPhase.Refining)
        val person = proposal.targetPersonId?.let(snapshot.value::person)
        val reply = gatewayClient.proposeChanges(
            input = correction,
            targetName = person?.name ?: proposal.targetName,
            snapshot = snapshot.value,
            person = person,
            now = Instant.now(),
            zoneId = ZoneId.systemDefault(),
            locale = Locale.getDefault().toLanguageTag(),
            history = ChatRouter.captureScopedTurns(history),
            previousProposal = proposal,
        )
        clearAttachment(messageId)
        appendAssistant(
            reply.assistantMessage,
            ChatAttachment.Proposal(reply.proposal, caveat = reply.caveat),
            fromGateway = true,
        )
    }

    private fun appendUser(text: String) = append(ChatRole.USER, text, null, false)

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
    ) {
        val message = ChatMessage(
            id = nextMessageId++,
            role = role,
            text = text,
            attachment = attachment,
            failed = failed,
            sentAt = System.currentTimeMillis(),
            fromGateway = fromGateway,
        )
        _chat.value = _chat.value.copy(messages = _chat.value.messages + message)
    }

    private fun updateMessage(messageId: Long, transform: (ChatMessage) -> ChatMessage) {
        _chat.value = _chat.value.copy(
            messages = _chat.value.messages.map { if (it.id == messageId) transform(it) else it },
        )
    }

    private fun clearAttachment(messageId: Long) = updateMessage(messageId) { it.copy(attachment = null) }

    /** The turn that produced a response, so a report shows both halves. */
    private fun precedingUserMessage(messageId: Long): String =
        _chat.value.messages.lastOrNull { it.id < messageId && it.role == ChatRole.USER }?.text.orEmpty()

    private fun messagesBefore(messageId: Long): List<ChatMessage> =
        _chat.value.messages.takeWhile { it.id != messageId }

    private fun setPhase(phase: ChatPhase) {
        _chat.value = _chat.value.copy(phase = phase)
    }

    /** A search held back until the user answers the full-network disclosure. */
    private data class PendingSearch(val query: String, val history: List<ChatMessage>)

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

    private fun aiFailureMessage(error: Throwable): String = when (error) {
        is GatewayException -> error.message ?: "The assistant could not complete the request."
        else -> error.message ?: "The assistant could not complete the request."
    }

    companion object {
        private const val AUTO_BACKUP_DELAY_MS = 1_500L
        private const val DRAFT_SAVE_DELAY_MS = 400L
    }
}
