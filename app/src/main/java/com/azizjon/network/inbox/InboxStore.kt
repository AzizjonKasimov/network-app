package com.azizjon.network.inbox

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keeps the inbox's private key and waiting drafts in encrypted preferences.
 *
 * They sit beside the GitHub token and the backup passphrase, which opens far
 * more than a draft, and like them they are never in Room or the backup. Opened
 * lazily, so creating the store costs nothing on the main thread.
 */
class InboxStore(private val context: Context) : InboxStorage {
    private val secrets by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFERENCES_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): InboxState =
        secrets.getString(KEY_STATE, null)?.let { runCatching { InboxState.fromJson(it) }.getOrNull() } ?: InboxState()

    override fun save(state: InboxState) {
        // commit, not apply: the caller deletes the draft from GitHub next.
        if (!secrets.edit().putString(KEY_STATE, state.toJson()).commit()) {
            throw IllegalStateException("Could not save the drafts from your PC on this phone")
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "pc_inbox"
        const val KEY_STATE = "state"
    }
}
