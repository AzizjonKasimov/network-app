package com.azizjon.network.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keeps the unsent capture note on disk.
 *
 * Nothing in the capture flow is persisted until the user applies a proposal, so
 * a process death while the assistant is working - which Android is free to
 * cause once the app is backgrounded - used to take the typed or dictated note
 * with it. A draft names real people, so it is encrypted at rest like the
 * gateway token rather than left in plain preferences.
 */
class CaptureDraftStore(context: Context) {
    private val secrets = EncryptedSharedPreferences.create(
        context,
        PREFERENCES_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun read(): String = secrets.getString(KEY_DRAFT, null).orEmpty()

    fun write(value: String) {
        if (value.isBlank()) clear() else secrets.edit().putString(KEY_DRAFT, value).apply()
    }

    fun clear() {
        secrets.edit().remove(KEY_DRAFT).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "capture_draft"
        const val KEY_DRAFT = "draft"
    }
}
