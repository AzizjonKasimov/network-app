package com.azizjon.network.inbox

import com.azizjon.network.backup.GitHubBackupConfig
import com.azizjon.network.backup.GitHubBackupException
import com.azizjon.network.backup.GitHubFile
import com.azizjon.network.backup.GitHubFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What one look at the inbox found. */
data class InboxCheck(
    val taken: Int = 0,
    /** Why each draft that could not be opened was deleted. */
    val removed: List<String> = emptyList(),
    /** True when `key.json` was missing or held another key, and this phone's was put back. */
    val keyRestored: Boolean = false,
)

/**
 * Drafts the user's PC queued for the Assistant, carried through the private
 * data repository.
 *
 * The PC encrypts each draft to this phone's public key, so the repository only
 * ever holds ciphertext. A draft is saved on the phone before it is deleted from
 * the repository, so a failure in between repeats a download rather than losing
 * a draft, and remembered ids keep a copy restored from git history from showing
 * twice. Nothing here sends anything to the assistant: a draft waits until the
 * user sends, edits, or discards it.
 *
 * Every method blocks, so call them off the main thread.
 */
class PcInbox(
    private val files: GitHubFiles,
    private val storage: InboxStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var loaded = false
    private val _state = MutableStateFlow(InboxState())
    val state: StateFlow<InboxState> = _state.asStateFlow()

    /** Reads what is kept on the phone, once. */
    fun load(): InboxState = update { it }

    /**
     * Makes a new key pair and publishes its public half as `key.json`.
     *
     * The key is saved before it is published, so a published key never lacks
     * its private half.
     */
    fun enable(config: GitHubBackupConfig): InboxState {
        requireConfigured(config)
        files.requirePrivateRepository(config)
        val key = InboxCodec.newKey(folder(config.path), clock())
        update { it.copy(key = key, lastError = "") }
        try {
            val existing = files.read(config, keyPath(key))
            val sha = files.write(config, keyPath(key), InboxCodec.keyDocument(key), existing?.sha, KEY_MESSAGE)
            return setRemoteSha(key, sha)
        } catch (error: Exception) {
            update { if (it.key == key) it.copy(key = null) else it }
            throw error
        }
    }

    /**
     * Turns the inbox off. The private key is deleted on the phone first, so
     * nothing queued from now on can ever be opened, then the folder is cleared
     * from the repository. Drafts already taken stay until they are used.
     */
    fun disable(config: GitHubBackupConfig) {
        val key = load().key ?: return
        update { it.copy(key = null, lastError = "") }
        requireConfigured(config)
        files.list(config, key.folder).forEach { files.delete(config, it.path, it.sha, OFF_MESSAGE) }
    }

    /** Takes whatever drafts are waiting in the repository, oldest first. */
    fun check(config: GitHubBackupConfig): InboxCheck {
        val key = load().key ?: return InboxCheck()
        return try {
            requireConfigured(config)
            files.requirePrivateRepository(config)
            val listing = files.list(config, key.folder)
            val keyRestored = keepKeyPublished(config, key, listing.firstOrNull { it.name == KEY_FILE })
            var taken = 0
            val removed = mutableListOf<String>()
            for (file in listing.filter { it.name.endsWith(DRAFT_SUFFIX) }.sortedBy { it.name }) {
                val now = _state.value
                // Turned off or full: the rest wait in the repository for next time.
                if (now.key?.publicKey != key.publicKey || now.pending.size >= MAX_WAITING) break
                when (val received = receive(config, key, file)) {
                    Received.New -> taken++
                    Received.Skipped -> Unit
                    is Received.Refused -> removed += received.reason
                }
            }
            update { it.copy(lastCheckAt = clock(), lastError = "") }
            InboxCheck(taken, removed, keyRestored)
        } catch (error: Exception) {
            update { it.copy(lastCheckAt = clock(), lastError = error.message ?: "The inbox could not be checked") }
            throw error
        }
    }

    /** Removes a waiting draft so it can be sent, edited, or discarded. */
    fun take(id: String): InboxDraft? {
        var draft: InboxDraft? = null
        update { state ->
            draft = state.pending.firstOrNull { it.id == id }
            if (draft == null) state else state.copy(pending = state.pending.filterNot { it.id == id })
        }
        return draft
    }

    private fun receive(config: GitHubBackupConfig, key: InboxKey, file: GitHubFile): Received {
        if (file.size > InboxCodec.MAX_ENVELOPE_BYTES) {
            files.delete(config, file.path, file.sha, TAKEN_MESSAGE)
            return Received.Refused("it is too large")
        }
        // Gone since the listing, say taken by another look at the inbox.
        val envelope = files.read(config, file.path) ?: return Received.Skipped
        val draft = try {
            InboxCodec.open(envelope.text, key)
        } catch (error: InboxCodecException) {
            files.delete(config, file.path, envelope.sha, TAKEN_MESSAGE)
            return Received.Refused(error.message.orEmpty())
        }
        var fresh = false
        update { state ->
            fresh = draft.id !in state.seen && state.pending.none { it.id == draft.id }
            if (!fresh) {
                state
            } else {
                state.copy(pending = state.pending + draft, seen = (state.seen + draft.id).takeLast(MAX_SEEN))
            }
        }
        // Only now that it is safe on the phone.
        files.delete(config, file.path, envelope.sha, TAKEN_MESSAGE)
        return if (fresh) Received.New else Received.Skipped
    }

    /**
     * Puts this phone's public key back when `key.json` is missing or holds a
     * different key. Only this phone writes it, so either means something else
     * changed the repository; the PC script refuses a swapped key on its own.
     */
    private fun keepKeyPublished(config: GitHubBackupConfig, key: InboxKey, listed: GitHubFile?): Boolean {
        if (listed != null && listed.sha == key.remoteSha) return false
        val published = listed?.let { files.read(config, it.path) }
        if (published != null && InboxCodec.publishedKey(published.text) == key.publicKey) {
            setRemoteSha(key, published.sha)
            return false
        }
        val sha = files.write(config, keyPath(key), InboxCodec.keyDocument(key), published?.sha, KEY_MESSAGE)
        setRemoteSha(key, sha)
        return true
    }

    private fun setRemoteSha(key: InboxKey, sha: String): InboxState = update { state ->
        val current = state.key
        if (current == null || current.publicKey != key.publicKey) state else state.copy(key = current.copy(remoteSha = sha))
    }

    private fun update(transform: (InboxState) -> InboxState): InboxState = synchronized(lock) {
        if (!loaded) {
            _state.value = storage.load()
            loaded = true
        }
        val next = transform(_state.value)
        if (next != _state.value) {
            storage.save(next)
            _state.value = next
        }
        next
    }

    private fun requireConfigured(config: GitHubBackupConfig) {
        if (!config.configured) throw GitHubBackupException("Complete and save the GitHub backup settings first")
    }

    private fun keyPath(key: InboxKey): String = "${key.folder}/$KEY_FILE"

    private sealed interface Received {
        data object New : Received
        data object Skipped : Received
        data class Refused(val reason: String) : Received
    }

    companion object {
        const val KEY_FILE = "key.json"
        const val DRAFT_SUFFIX = ".enc.json"
        const val MAX_WAITING = 20
        const val MAX_SEEN = 200
        private const val KEY_MESSAGE = "inbox(android): key"
        private const val TAKEN_MESSAGE = "inbox(android): taken"
        private const val OFF_MESSAGE = "inbox(android): off"

        /**
         * The repository folder a backup path's drafts arrive in. Named after
         * the backup path, so an emulator pointed at a test backup, as testing
         * requires, also gets an inbox of its own and never takes the phone's
         * drafts. `scripts/queue-draft.ps1` names it the same way.
         */
        fun folder(backupPath: String): String {
            val path = backupPath.trim().trimStart('/')
            val base = when {
                path.endsWith(".enc.json") -> path.removeSuffix(".enc.json")
                path.endsWith(".json") -> path.removeSuffix(".json")
                else -> path
            }
            return "$base-inbox"
        }
    }
}
