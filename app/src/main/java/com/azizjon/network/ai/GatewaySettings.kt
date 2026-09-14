package com.azizjon.network.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class GatewaySettingsState(
    val tokenSaved: Boolean = false,
    /** The user accepted that the assistant reads whatever records it needs. */
    val assistantConsent: Boolean = false,
)

/**
 * Holds the bearer token for the private AI gateway, encrypted at rest.
 *
 * The token is entered by the user rather than shipped in the APK: releases are
 * public, and a bundled token could be extracted from one and used to spend the
 * owner's Claude subscription.
 */
class GatewaySettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secrets = EncryptedSharedPreferences.create(
        context,
        SECRET_PREFERENCES_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    init {
        // Drop the superseded Gemini credentials so a live API key is not left
        // behind on devices upgrading from an older release. Deleting the file
        // avoids having to decrypt it with the old key scheme.
        runCatching {
            context.deleteSharedPreferences(LEGACY_SECRET_PREFERENCES_NAME)
            context.deleteSharedPreferences(LEGACY_PREFERENCES_NAME)
        }
        // The old consent covered searches sending the network. The assistant now
        // reads any record whenever it decides to, which is a different promise,
        // so it is asked for again rather than carried over.
        if (preferences.contains(LEGACY_KEY_SEARCH_CONSENT)) {
            preferences.edit().remove(LEGACY_KEY_SEARCH_CONSENT).apply()
        }
    }

    val state: GatewaySettingsState
        get() = GatewaySettingsState(
            tokenSaved = !token().isNullOrBlank(),
            assistantConsent = preferences.getBoolean(KEY_ASSISTANT_CONSENT, false),
        )

    fun token(): String? = secrets.getString(KEY_TOKEN, null)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    fun saveToken(value: String) {
        val clean = value.trim()
        require(clean.isNotEmpty()) { "Enter an access token" }
        require(clean.length <= MAX_TOKEN_CHARACTERS) { "The access token is too long" }
        secrets.edit().putString(KEY_TOKEN, clean).apply()
    }

    fun clearToken() {
        secrets.edit().remove(KEY_TOKEN).apply()
    }

    fun setAssistantConsent(accepted: Boolean) {
        preferences.edit().putBoolean(KEY_ASSISTANT_CONSENT, accepted).apply()
    }

    companion object {
        const val MAX_TOKEN_CHARACTERS = 512

        private const val PREFERENCES_NAME = "gateway_settings"
        private const val SECRET_PREFERENCES_NAME = "gateway_credentials"
        private const val KEY_TOKEN = "token"
        private const val KEY_ASSISTANT_CONSENT = "assistant_consent"
        private const val LEGACY_KEY_SEARCH_CONSENT = "full_network_search_consent"

        private const val LEGACY_PREFERENCES_NAME = "gemini_settings"
        private const val LEGACY_SECRET_PREFERENCES_NAME = "gemini_credentials"
    }
}
