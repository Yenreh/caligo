package com.yenreh.caligo.trips

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.yenreh.caligo.MainActivity
import com.yenreh.caligo.asChannelBeforeO
import com.yenreh.caligo.channelOn
import com.yenreh.caligo.createChannels
import com.yenreh.caligo.R
import com.yenreh.caligo.waits.Alerts

/**
 * The trip's notification: its channels, its id, and each notice built
 * into a notification with its action. Only [TripService] posts and
 * cancels it. Its ids are apart from the waits', so the two services
 * never touch each other's notifications.
 */
object TripNotifications {
    /** The one trip's notification, the trip service's own */
    const val TRIP_ID = 4201

    /** Shown only when a start finds no trip to carry the service */
    const val PLACEHOLDER_ID = 4200

    /** The last word of a trip that ended by itself: gone by its timeout, a tap, the next start or the next process */
    const val ENDED_ID = 4202

    /** How long the last word of a trip stays */
    const val ENDED_TIMEOUT_MS = 3 * 60_000L


    const val ACTION_FINISH = "com.yenreh.caligo.trip.FINISH"
    const val ACTION_DISMISSED = "com.yenreh.caligo.trip.DISMISSED"

    /** "I took the A47": the trip takes that line for the ride it awaits */
    const val ACTION_TAKE_LINE = "com.yenreh.caligo.trip.TAKE_LINE"
    const val EXTRA_LINE = "line"

    /** Opens the trip screen in the app */
    const val ACTION_OPEN_TRIP = "com.yenreh.caligo.OPEN_TRIP"

    /** The trip [intent]'s action was posted for, so a late one ends no newer trip */
    const val EXTRA_STARTED_AT = "startedAt"

    private val VIBRATION = longArrayOf(0, 250, 150, 250, 150, 250)

    /** The channels still missing, as [createChannels] makes them */
    fun createChannels(context: Context) = createChannels(context, TripChannel.entries, VIBRATION)

    /**
     * Whether a trip may post: the app's notifications on, and the trips'
     * channel. The warning's only silences the warning, and the waits'
     * have no say: the trip's notification carries its awaited buses.
     */
    fun allowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() && channelOn(context, TripChannel.TRIPS)

    /** The settings that let a trip post: the trips' channel when only it is off, else the app's notifications */
    fun settingsIntent(context: Context): Intent {
        val appOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val intent = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            appOn && !channelOn(context, TripChannel.TRIPS) -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, TripChannel.TRIPS.id)
            else -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** At every process start no trip runs: a notification a killed process left goes */
    fun cancelLeftovers(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(PLACEHOLDER_ID)
        manager.cancel(TRIP_ID)
        manager.cancel(ENDED_ID)
    }

    /** [notice] as the last word of a trip: not ongoing, a tap opens the trip screen's ending */
    fun ended(context: Context, notice: EndedNotice, alerts: Alerts): Notification {
        val builder = NotificationCompat.Builder(context, notice.channel.id)
            .setSmallIcon(R.drawable.ic_directions_bus)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            // Whole once expanded, at any text size
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(notice.title).bigText(notice.text))
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setTimeoutAfter(ENDED_TIMEOUT_MS)
            // Gone within minutes: a clock would say little
            .setShowWhen(false)
            .setContentIntent(openTrip(context))
        return builder.asChannelBeforeO(notice.alerting, alerts, VIBRATION).build()
    }

    /** [notice] of the trip started at [startedAt] as its notification */
    fun build(context: Context, startedAt: Long, notice: TripNotice, alerts: Alerts, words: TripWords): Notification {
        val builder = NotificationCompat.Builder(context, notice.channelId)
            .setSmallIcon(R.drawable.ic_directions_bus)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setTicker(notice.summary)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOngoing(true)
            // Updates never sound; the warning may, once
            .setOnlyAlertOnce(!notice.alerting)
            .setTimeoutAfter(notice.timeoutAfter)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openTrip(context))
            .setDeleteIntent(action(context, startedAt, ACTION_DISMISSED))
            // The minutes are in the title and the chip: no clock to repeat them
            .setShowWhen(false)
            // Older versions draw this bar; Android 16 and later the style's
            .setProgress(notice.progressMax, notice.progress, false)
            // A Live Update on Android 16 and later: promoted while ongoing,
            // with the minutes as its status bar chip
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(notice.chip ?: "")
            .setStyle(
                NotificationCompat.ProgressStyle()
                    .setStyledByProgress(true)
                    .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(notice.progressMax)))
                    .setProgressPoints(notice.points.map { NotificationCompat.ProgressStyle.Point(it.position) })
                    .setProgress(notice.progress)
                    .setProgressTrackerIcon(IconCompat.createWithResource(context, trackerIcon(notice.tracker))),
            )
        // The lines to take first, soonest first, then Finish: three at most, as Android shows
        for ((i, line) in notice.takeLines.withIndex()) builder.addAction(0, words.tookLine(line), takeLine(context, startedAt, i, line))
        builder.addAction(0, words.finish, action(context, startedAt, ACTION_FINISH))
        return builder.asChannelBeforeO(notice.alerting, alerts, VIBRATION).build()
    }

    private fun trackerIcon(tracker: TripTracker) = when (tracker) {
        TripTracker.BUS -> R.drawable.ic_progress_tracker
        TripTracker.WALKER -> R.drawable.ic_progress_walker
    }

    private fun action(context: Context, startedAt: Long, name: String): PendingIntent {
        val intent = Intent(context, TripActionReceiver::class.java)
            .setAction(name)
            .putExtra(EXTRA_STARTED_AT, startedAt)
        return PendingIntent.getBroadcast(
            context,
            if (name == ACTION_FINISH) 1 else 2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** "I took the [line]", the [slot]-th button: straight to the trip service, which runs already */
    private fun takeLine(context: Context, startedAt: Long, slot: Int, line: String): PendingIntent {
        val intent = Intent(context, TripActionReceiver::class.java)
            .setAction(ACTION_TAKE_LINE)
            .putExtra(EXTRA_STARTED_AT, startedAt)
            .putExtra(EXTRA_LINE, line)
        return PendingIntent.getBroadcast(
            context,
            TAKE_LINE_CODE + slot,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val TAKE_LINE_CODE = 10

    /** The trip screen, over whatever the app shows */
    fun openTrip(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_TRIP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, TRIP_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Carries the service for the moment a start finds nothing to follow */
    fun placeholder(context: Context): Notification =
        NotificationCompat.Builder(context, TripChannel.TRIPS.id)
            .setSmallIcon(R.drawable.ic_directions_bus)
            .setContentTitle(context.getString(R.string.trip_channel))
            .setSilent(true)
            .setTimeoutAfter(TripNotice.TIMEOUT_MARGIN_MS)
            .build()
}
