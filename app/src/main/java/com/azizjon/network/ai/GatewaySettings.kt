package com.azizjon.network.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class GatewaySettingsState(
    val tokenSaved: Boolean = false,
    val fullNetworkSearchConsent: Boolean = false,
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
    }

    val state: GatewaySettingsState
        get() = GatewaySettingsState(
            tokenSaved = !token().isNullOrBlank(),
            fullNetworkSearchConsent = preferences.getBoolean(KEY_SEARCH_CONSENT, false),
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

    fun setFullNetworkSearchConsent(accepted: Boolean) {
        preferences.edit().putBoolean(KEY_SEARCH_CONSENT, accepted).apply()
    }

    companion object {
        const val MAX_TOKEN_CHARACTERS = 512

        private const val PREFERENCES_NAME = "gateway_settings"
        private const val SECRET_PREFERENCES_NAME = "gateway_credentials"
        private const val KEY_TOKEN = "token"
        private const val KEY_SEARCH_CONSENT = "full_network_search_consent"

        private const val LEGACY_PREFERENCES_NAME = "gemini_settings"
        private const val LEGACY_SECRET_PREFERENCES_NAME = "gemini_credentials"
    }
}
