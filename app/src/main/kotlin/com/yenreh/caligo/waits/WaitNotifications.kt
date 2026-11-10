package com.yenreh.caligo.waits

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.yenreh.caligo.MainActivity
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.domain.WaitKey

/**
 * The waits' notifications: their channels, their ids, and each notice
 * built into a notification with its actions. Only [WaitService] posts
 * and cancels them.
 */
object WaitNotifications {
    /** One id per wait at once: [FIRST_ID] and the next [Wait.CAP] - 1 */
    const val FIRST_ID = 4101

    /** Shown only when a start finds no wait to carry the service */
    const val PLACEHOLDER_ID = 4100

    val ids: IntRange get() = FIRST_ID until FIRST_ID + Wait.CAP

    const val ACTION_TOOK = "com.yenreh.caligo.wait.TOOK"
    const val ACTION_STOP = "com.yenreh.caligo.wait.STOP"
    const val ACTION_DISMISSED = "com.yenreh.caligo.wait.DISMISSED"

    /** Opens the stop's details in the app */
    const val ACTION_OPEN_STOP = "com.yenreh.caligo.OPEN_STOP"

    const val EXTRA_STOP_ID = "stopId"
    const val EXTRA_VEHICLE_ID = "vehicleId"
    const val EXTRA_STOP_NAME = "stopName"
    const val EXTRA_ANCHOR_LATITUDE = "anchorLatitude"
    const val EXTRA_ANCHOR_LONGITUDE = "anchorLongitude"
    const val EXTRA_LATITUDE = "latitude"
    const val EXTRA_LONGITUDE = "longitude"

    private val VIBRATION = longArrayOf(0, 250, 150, 250)

    /**
     * The channels still missing. One made is never made again nor
     * altered: whatever the user set for it in the system stays.
     */
    fun createChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        for (channel in WaitChannel.entries) {
            if (manager.getNotificationChannelCompat(channel.id) != null) continue
            val builder = when (channel) {
                WaitChannel.WAITS -> NotificationChannelCompat.Builder(channel.id, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.wait_channel))
                    .setDescription(context.getString(R.string.wait_channel_description))
                else -> NotificationChannelCompat.Builder(channel.id, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(alertName(channel)))
                    .setDescription(context.getString(R.string.alert_channel_description))
            }
            builder.setShowBadge(false)
            if (channel.sound) {
                builder.setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            } else {
                builder.setSound(null, null)
            }
            builder.setVibrationEnabled(channel.vibrate)
            if (channel.vibrate) builder.setVibrationPattern(VIBRATION)
            manager.createNotificationChannel(builder.build())
        }
    }

    private fun alertName(channel: WaitChannel) = when (channel) {
        WaitChannel.ALERT_VIBRATE -> R.string.alert_channel_vibrate
        WaitChannel.ALERT_SOUND -> R.string.alert_channel_sound
        WaitChannel.ALERT_SOUND_VIBRATE -> R.string.alert_channel_sound_vibrate
        else -> R.string.alert_channel_silent
    }

    /** Whether [channel] is on: not turned off by the user, or not made yet */
    fun channelOn(context: Context, channel: WaitChannel): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val made = NotificationManagerCompat.from(context).getNotificationChannelCompat(channel.id) ?: return true
        return made.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    /**
     * At every process start no wait runs yet, so none of their
     * notifications may stay: one left by a process killed without warning
     * goes here, if its timeout has not removed it already
     */
    fun cancelLeftovers(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(PLACEHOLDER_ID)
        for (id in ids) manager.cancel(id)
    }

    /** The wait [intent] names, as its actions carry it */
    fun keyOf(intent: Intent): WaitKey? {
        val stop = intent.getStringExtra(EXTRA_STOP_ID) ?: return null
        val vehicle = intent.getStringExtra(EXTRA_VEHICLE_ID) ?: return null
        return WaitKey(stop, vehicle)
    }

    /** [notice] of [wait] as the notification with [id] */
    fun build(context: Context, id: Int, wait: Wait, notice: WaitNotice, alerts: Alerts, words: WaitWords): Notification {
        val builder = NotificationCompat.Builder(context, notice.channel.id)
            .setSmallIcon(R.drawable.ic_directions_bus)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setTicker(notice.summary)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            // Updates never sound; the alert may, once
            .setOnlyAlertOnce(!notice.alerting)
            .setTimeoutAfter(notice.timeoutAfter)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openStop(context, id, wait))
            .setDeleteIntent(action(context, id, wait, ACTION_DISMISSED))
            // Older versions draw this bar; Android 16 and later the style's
            .setProgress(notice.progressMax, notice.progress, false)
            // A Live Update on Android 16 and later: promoted while ongoing,
            // with the minutes as its status bar chip
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(notice.chip ?: "")
            .setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(notice.progressMax)))
                    .setProgress(notice.progress)
                    .setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.ic_directions_bus)),
            )
        for (action in notice.actions) {
            val name = when (action) {
                WaitAction.TOOK -> ACTION_TOOK
                WaitAction.STOP -> ACTION_STOP
            }
            builder.addAction(0, words.action(action), action(context, id, wait, name))
        }
        val countdownTo = notice.countdownTo
        if (countdownTo != null) {
            builder.setWhen(countdownTo).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false)
        }
        // No channels before Android 8: the notification carries what its channel would
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (notice.alerting) {
                builder.priority = NotificationCompat.PRIORITY_HIGH
                if (alerts.sound) builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                if (alerts.vibrate) builder.setVibrate(VIBRATION)
            } else {
                builder.priority = NotificationCompat.PRIORITY_LOW
            }
        }
        return builder.build()
    }

    private fun action(context: Context, id: Int, wait: Wait, name: String): PendingIntent {
        val intent = Intent(context, WaitActionReceiver::class.java)
            .setAction(name)
            .putExtra(EXTRA_STOP_ID, wait.bus.stopId)
            .putExtra(EXTRA_VEHICLE_ID, wait.bus.bus.vehicleId)
        val code = (id - PLACEHOLDER_ID) * 4 + listOf(ACTION_TOOK, ACTION_STOP, ACTION_DISMISSED).indexOf(name)
        return PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openStop(context: Context, id: Int, wait: Wait): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_STOP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_STOP_ID, wait.bus.stopId)
            .putExtra(EXTRA_STOP_NAME, wait.bus.stopName)
            .putExtra(EXTRA_ANCHOR_LATITUDE, wait.bus.anchor.latitude)
            .putExtra(EXTRA_ANCHOR_LONGITUDE, wait.bus.anchor.longitude)
        // The stop's own place, when known: its details ask around it
        wait.point?.takeIf { it.placed }?.let {
            intent.putExtra(EXTRA_LATITUDE, it.latitude).putExtra(EXTRA_LONGITUDE, it.longitude)
        }
        return PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Carries the service for the moment a start finds nothing to wait for */
    fun placeholder(context: Context): Notification =
        NotificationCompat.Builder(context, WaitChannel.WAITS.id)
            .setSmallIcon(R.drawable.ic_directions_bus)
            .setContentTitle(context.getString(R.string.wait_channel))
            .setSilent(true)
            .setTimeoutAfter(WaitNotice.TIMEOUT_MARGIN_MS)
            .build()
}
