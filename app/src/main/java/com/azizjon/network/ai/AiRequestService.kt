package com.azizjon.network.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.azizjon.network.MainActivity
import com.azizjon.network.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Holds the app out of the cached process state while a gateway request is open.
 *
 * A capture takes tens of seconds. Android freezes cached processes and may kill
 * them outright, so leaving the app mid-request used to stall the reply or lose
 * it. A short-lived foreground service keeps the process at foreground priority
 * until the reply lands. It carries no state of its own - the request still runs
 * in the view model, and this only stops the system from suspending it. Swiping
 * the app away from recents still ends the work.
 */
class AiRequestService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        return START_NOT_STICKY
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(getString(R.string.ai_request_notification_title))
        .setContentText(getString(R.string.ai_request_notification_text))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()
        .also { ensureChannel() }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ai_request_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
    }

    companion object {
        private const val CHANNEL_ID = "ai_requests"
        private const val NOTIFICATION_ID = 4201

        /** Requests in flight. The service runs while this is above zero. */
        private val holders = AtomicInteger(0)

        /**
         * Runs [block] with the process pinned in the foreground.
         *
         * Start failures are swallowed on purpose: the request does not depend on
         * the service, and a slower capture beats a crash if the system refuses
         * to start one.
         */
        suspend fun <T> holdingProcess(context: Context, block: suspend () -> T): T {
            acquire(context)
            try {
                return block()
            } finally {
                release(context)
            }
        }

        private fun acquire(context: Context) {
            if (holders.getAndIncrement() != 0) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, AiRequestService::class.java))
            }
        }

        private fun release(context: Context) {
            if (holders.decrementAndGet() > 0) return
            holders.set(0)
            runCatching { context.stopService(Intent(context, AiRequestService::class.java)) }
        }
    }
}
