package com.azizjon.network.checkin

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.azizjon.network.NetworkApplication

/**
 * Notes who wrote in Telegram, WhatsApp, KakaoTalk, or LinkedIn, so the
 * evening check-in can ask about them.
 *
 * Android hands a notification listener every notification on the phone. This
 * one returns at once for any app other than those four, and from theirs keeps
 * only the sender's name and the time. Message text is never stored or logged;
 * LinkedIn's is read only to spot a new connection.
 */
class ChatNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val source = ChatSources.sourceFor(sbn.packageName, debuggable) ?: return
        val app = application as? NetworkApplication ?: return
        if (!app.checkinSettings.chatsEnabled) return
        val posted = runCatching { read(sbn, source) }.getOrNull() ?: return
        val sightings = ChatNotificationParser.parse(posted)
        if (sightings.isNotEmpty()) app.checkins.recordSightings(sightings, sbn.postTime)
    }

    private fun read(sbn: StatusBarNotification, source: String): PostedChat {
        val notification = sbn.notification
        val extras = notification.extras
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val self = style?.user?.name?.toString()
        // A message from the user carries no person, or the user's own.
        val senders = style?.messages.orEmpty()
            .mapNotNull { it.person?.name?.toString() }
            .filter { it != self }
        return PostedChat(
            source = source,
            category = notification.category,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isGroupConversation = style?.isGroupConversation == true ||
                extras.getBoolean(NotificationCompat.EXTRA_IS_GROUP_CONVERSATION),
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            senders = senders,
            text = if (source == ChatSources.LINKEDIN) extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() else null,
        )
    }
}
