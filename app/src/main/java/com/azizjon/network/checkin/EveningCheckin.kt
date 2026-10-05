package com.azizjon.network.checkin

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.azizjon.network.MainActivity
import com.azizjon.network.NetworkApplication
import com.azizjon.network.R
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * The once-a-day reminder that opens the check-in.
 *
 * Each run schedules the next, a day ahead, as one-time work rather than
 * periodic work: periodic work drifts later each day it is delayed, and an
 * "evening" reminder that has crept to midnight is no use.
 */
object EveningCheckin {
    const val EXTRA_OPEN = "com.azizjon.network.extra.OPEN"
    const val OPEN_CHECKIN = "checkin"
    const val OPEN_ASSISTANT = "assistant"

    private const val WORK_NAME = "evening_checkin"
    private const val CHANNEL_ID = "checkin"
    private const val NOTIFICATION_ID = 4301

    /** A reminder that could not run until this long after its time (phone off, say) is skipped for the day. */
    private val LATE_LIMIT: Duration = Duration.ofHours(3)

    /**
     * Puts the reminder on the schedule, or takes it off. [replace] moves an
     * already scheduled one, after the time changed; otherwise an existing
     * schedule is left as it is.
     */
    fun schedule(context: Context, state: CheckinSettingsState, replace: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!state.eveningEnabled) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        manager.enqueueUniqueWork(
            WORK_NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request(state.eveningMinutes),
        )
    }

    /** Called by the run itself, which is still in progress, so the next one queues behind it. */
    private fun scheduleNext(context: Context, minutes: Int) {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(minutes))
    }

    private fun request(minutes: Int) = OneTimeWorkRequestBuilder<EveningCheckinWorker>()
        .setInitialDelay(delayUntil(minutes, ZonedDateTime.now()).toMillis(), TimeUnit.MILLISECONDS)
        .build()

    /** From [now] to the next [minutes] past midnight: today's if still ahead, else tomorrow's. */
    fun delayUntil(minutes: Int, now: ZonedDateTime): Duration {
        val today = now.toLocalDate().atStartOfDay(now.zone).plusMinutes(minutes.toLong())
        val next = if (today.isAfter(now)) today else today.plusDays(1)
        return Duration.between(now, next)
    }

    /** True unless the run comes so long after its time that the day's reminder no longer makes sense. */
    fun onTime(minutes: Int, now: ZonedDateTime): Boolean {
        val today = now.toLocalDate().atStartOfDay(now.zone).plusMinutes(minutes.toLong())
        val target = if (today.isAfter(now)) today.minusDays(1) else today
        return Duration.between(target, now) <= LATE_LIMIT
    }

    internal suspend fun run(context: Context) {
        val app = context.applicationContext as NetworkApplication
        val state = app.checkinSettings.state
        if (!state.eveningEnabled) return
        try {
            if (onTime(state.eveningMinutes, ZonedDateTime.now())) {
                if (state.contactsEnabled) app.checkins.refreshContacts()
                val list = CheckinRules.build(app.checkins.all(), app.repository.snapshot(), System.currentTimeMillis())
                notify(context, list.count)
            }
        } finally {
            scheduleNext(context, state.eveningMinutes)
        }
    }

    // The permission is checked first; lint cannot follow that into notify().
    @SuppressLint("MissingPermission")
    private fun notify(context: Context, count: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val text = if (count > 0) {
            "${if (count == 1) "1 thing" else "$count things"} to look at: people you met and details that may have changed."
        } else {
            "Did you meet or talk to anyone today?"
        }
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN, if (count > 0) OPEN_CHECKIN else OPEN_ASSISTANT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Check-in")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.checkin_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
}

class EveningCheckinWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            EveningCheckin.run(applicationContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Tomorrow's run is queued behind this one, and a failed run would
            // fail it too: one bad evening must not end the reminders.
        }
        return Result.success()
    }
}
