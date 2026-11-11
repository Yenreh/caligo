package com.yenreh.caligo

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yenreh.caligo.waits.Alerts

/**
 * One of the app's notification channels. Each is made once and never
 * altered, so what the user sets for it in the system wins. A channel's
 * importance cannot change, so one that needs another takes a new id and
 * [replaces] the old.
 */
interface AppChannel {
    val id: String

    /** Its name and description in the system's settings */
    @get:StringRes val title: Int

    @get:StringRes val description: Int

    /** Of high importance, so it peeks; else of default importance, shown on the lock screen yet never peeking */
    val urgent: Boolean
    val sound: Boolean
    val vibrate: Boolean
    val replaces: String?
}

/**
 * The [channels] still missing; one made is never made again nor altered.
 * One that replaces another is made off if the user had turned that one
 * off, and the other goes. Those that vibrate do so as [vibration].
 */
fun createChannels(context: Context, channels: List<AppChannel>, vibration: LongArray) {
    val manager = NotificationManagerCompat.from(context)
    for (channel in channels) {
        // Read before it goes: a replaced channel the user turned off stays off
        val replaced = channel.replaces?.let { manager.getNotificationChannelCompat(it) }
        if (replaced != null) manager.deleteNotificationChannel(replaced.id)
        if (manager.getNotificationChannelCompat(channel.id) != null) continue
        val importance = when {
            replaced?.importance == NotificationManagerCompat.IMPORTANCE_NONE -> NotificationManagerCompat.IMPORTANCE_NONE
            channel.urgent -> NotificationManagerCompat.IMPORTANCE_HIGH
            else -> NotificationManagerCompat.IMPORTANCE_DEFAULT
        }
        val builder = NotificationChannelCompat.Builder(channel.id, importance)
            .setName(context.getString(channel.title))
            .setDescription(context.getString(channel.description))
            .setShowBadge(false)
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
        if (channel.vibrate) builder.setVibrationPattern(vibration)
        manager.createNotificationChannel(builder.build())
    }
}

/** Whether [channel] is on: not turned off by the user, nor the one it replaces, or not made yet */
fun channelOn(context: Context, channel: AppChannel): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
    val manager = NotificationManagerCompat.from(context)
    // Before the channels are set up, the one it replaces says
    val made = manager.getNotificationChannelCompat(channel.id)
        ?: channel.replaces?.let { manager.getNotificationChannelCompat(it) }
        ?: return true
    return made.importance != NotificationManagerCompat.IMPORTANCE_NONE
}

/**
 * No channels before Android 8: the notification carries what its
 * channel would. [alerting], it peeks and sounds and vibrates as [alerts]
 * ask, as [vibration]; otherwise it is as the channel of updates: shown on
 * the lock screen, silent all the same.
 */
fun NotificationCompat.Builder.asChannelBeforeO(alerting: Boolean, alerts: Alerts, vibration: LongArray): NotificationCompat.Builder {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return this
    if (alerting) {
        priority = NotificationCompat.PRIORITY_HIGH
        if (alerts.sound) setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        if (alerts.vibrate) setVibrate(vibration)
    } else {
        priority = NotificationCompat.PRIORITY_DEFAULT
    }
    return this
}
