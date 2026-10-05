package com.azizjon.network.checkin

import android.content.Context

data class CheckinSettingsState(
    val eveningEnabled: Boolean = false,
    /** Minutes after midnight, local time. */
    val eveningMinutes: Int = CheckinSettings.DEFAULT_EVENING_MINUTES,
    val contactsEnabled: Boolean = false,
    val chatsEnabled: Boolean = false,
)

/**
 * Which check-in sources the user turned on, and when the evening reminder comes.
 *
 * Everything starts off: each source needs an Android permission that only the
 * user can grant, so the check-in tab offers them one at a time.
 */
class CheckinSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val state: CheckinSettingsState
        get() = CheckinSettingsState(
            eveningEnabled = preferences.getBoolean(KEY_EVENING, false),
            eveningMinutes = preferences.getInt(KEY_EVENING_MINUTES, DEFAULT_EVENING_MINUTES),
            contactsEnabled = preferences.getBoolean(KEY_CONTACTS, false),
            chatsEnabled = preferences.getBoolean(KEY_CHATS, false),
        )

    /** Read on every chat notification, so it is a plain preference lookup. */
    val chatsEnabled: Boolean get() = preferences.getBoolean(KEY_CHATS, false)

    fun setEvening(enabled: Boolean, minutes: Int = state.eveningMinutes) {
        preferences.edit()
            .putBoolean(KEY_EVENING, enabled)
            .putInt(KEY_EVENING_MINUTES, minutes.coerceIn(0, MINUTES_PER_DAY - 1))
            .apply()
    }

    fun setContacts(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CONTACTS, enabled).apply()
    }

    fun setChats(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CHATS, enabled).apply()
    }

    companion object {
        const val DEFAULT_EVENING_MINUTES = 21 * 60
        const val MINUTES_PER_DAY = 24 * 60

        private const val PREFERENCES_NAME = "checkin_settings"
        private const val KEY_EVENING = "evening_enabled"
        private const val KEY_EVENING_MINUTES = "evening_minutes"
        private const val KEY_CONTACTS = "contacts_enabled"
        private const val KEY_CHATS = "chats_enabled"
    }
}
