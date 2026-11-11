package com.yenreh.caligo.waits

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.yenreh.caligo.MainActivity
import com.yenreh.caligo.asChannelBeforeO
import com.yenreh.caligo.createChannels
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.TookRide
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.domain.WaitKey

/**
 * The waits' notifications: their channels, their ids, and each notice
 * built into a notification with its actions. Only [WaitService] posts
 * and cancels them: the waits a trip starts post nothing of their own,
 * the trip's notification carrying them.
 */
object WaitNotifications {
    /** One id per wait at once: [FIRST_ID] and the next [Wait.CAP] - 1 */
    const val FIRST_ID = 4101

    /** Shown only when a start finds no wait to carry the service */
    const val PLACEHOLDER_ID = 4100

    val ids: IntRange get() = FIRST_ID until FIRST_ID + Wait.CAP

    /** "I took it": opens the trip screen in the app, which ends the wait */
    const val ACTION_TOOK = "com.yenreh.caligo.wait.TOOK"
    const val ACTION_STOP = "com.yenreh.caligo.wait.STOP"
    const val ACTION_DISMISSED = "com.yenreh.caligo.wait.DISMISSED"

    /** "Stop waiting" and the swipe of a stop's group: every wait of the group the wait named is in ends */
    const val ACTION_STOP_GROUP = "com.yenreh.caligo.wait.STOP_GROUP"
    const val ACTION_DISMISSED_GROUP = "com.yenreh.caligo.wait.DISMISSED_GROUP"

    /** Opens the stop's details in the app */
    const val ACTION_OPEN_STOP = "com.yenreh.caligo.OPEN_STOP"

    const val EXTRA_STOP_ID = "stopId"
    const val EXTRA_VEHICLE_ID = "vehicleId"
    const val EXTRA_TRIP = "trip"
    const val EXTRA_STOP_NAME = "stopName"
    const val EXTRA_ANCHOR_LATITUDE = "anchorLatitude"
    const val EXTRA_ANCHOR_LONGITUDE = "anchorLongitude"
    const val EXTRA_LATITUDE = "latitude"
    const val EXTRA_LONGITUDE = "longitude"
    const val EXTRA_LINE = "line"
    const val EXTRA_DESTINATION = "destination"

    private val VIBRATION = longArrayOf(0, 250, 150, 250)

    /** The channels still missing, as [createChannels] makes them */
    fun createChannels(context: Context) = createChannels(context, WaitChannel.entries, VIBRATION)

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
        if (!intent.hasExtra(EXTRA_TRIP)) return null
        return WaitKey(stop, vehicle, intent.getLongExtra(EXTRA_TRIP, 0))
    }

    /** [notice] of [wait] as the notification with [id] */
    fun build(context: Context, id: Int, wait: Wait, notice: WaitNotice, alerts: Alerts, words: WaitWords, trips: Boolean = false): Notification =
        build(context, id, listOf(wait), notice, alerts, words, trips)

    /**
     * [notice] of [waits], one alone or a stop's group in the order they
     * started, as the notification with [id]: a tap opens their stop, a
     * swipe ends them all. "I took it" opens the trip screen in a build
     * with [trips], else ends that wait.
     */
    fun build(
        context: Context,
        id: Int,
        waits: List<Wait>,
        notice: WaitNotice,
        alerts: Alerts,
        words: WaitWords,
        trips: Boolean = false,
    ): Notification {
        val wait = waits.first()
        val grouped = waits.size > 1
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
            .setDeleteIntent(action(context, id, wait, if (grouped) ACTION_DISMISSED_GROUP else ACTION_DISMISSED))
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
                    .setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.ic_progress_tracker)),
            )
        for (action in notice.actions) {
            val intent = when (action) {
                WaitAction.TOOK, WaitAction.YES -> tookIt(context, id, wait, trips = trips)
                WaitAction.STOP, WaitAction.NOT_TAKEN -> action(context, id, wait, ACTION_STOP)
                WaitAction.STOP_ALL -> action(context, id, wait, ACTION_STOP_GROUP)
            }
            builder.addAction(0, words.action(action), intent)
        }
        // A stop's group: each button for its own wait
        for ((slot, button) in notice.buttons.withIndex()) {
            val own = waits.firstOrNull { it.key == button.key } ?: continue
            val intent = when (button.action) {
                WaitAction.TOOK, WaitAction.YES -> tookIt(context, id, own, slot, trips)
                WaitAction.STOP, WaitAction.NOT_TAKEN -> action(context, id, own, ACTION_STOP)
                WaitAction.STOP_ALL -> action(context, id, own, ACTION_STOP_GROUP)
            }
            val label = if (button.action == WaitAction.TOOK && button.line != null) words.tookLine(button.line) else words.action(button.action)
            builder.addAction(0, label, intent)
        }
        // No clock, as the trip's: it would repeat the title's minutes, and in a group not say whose
        builder.setShowWhen(false)
        return builder.asChannelBeforeO(notice.alerting, alerts, VIBRATION).build()
    }

    private fun action(context: Context, id: Int, wait: Wait, name: String): PendingIntent {
        val intent = Intent(context, WaitActionReceiver::class.java)
            .setAction(name)
            .putExtra(EXTRA_STOP_ID, wait.bus.stopId)
            .putExtra(EXTRA_VEHICLE_ID, wait.bus.bus.vehicleId)
            .putExtra(EXTRA_TRIP, wait.key.trip)
        val code = (id - PLACEHOLDER_ID) * 4 + listOf(ACTION_STOP, ACTION_DISMISSED, ACTION_STOP_GROUP, ACTION_DISMISSED_GROUP).indexOf(name)
        return PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * "I took it": with [trips], the trip screen, opened straight from the
     * button, as Android 12 and later require of a notification's action
     * that opens the app; with the app on show the trip may follow the
     * location with the while-in-use permission alone. Without, the wait
     * ends there, as in 5.1, with no screen opened.
     */
    private fun tookIt(context: Context, id: Int, wait: Wait, slot: Int = 0, trips: Boolean): PendingIntent {
        val code = id + TOOK_REQUEST_OFFSET + slot * TOOK_SLOT_STEP
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        if (!trips) {
            val intent = Intent(context, WaitActionReceiver::class.java)
                .setAction(ACTION_TOOK)
                .putExtra(EXTRA_STOP_ID, wait.bus.stopId)
                .putExtra(EXTRA_VEHICLE_ID, wait.bus.bus.vehicleId)
                .putExtra(EXTRA_TRIP, wait.key.trip)
            return PendingIntent.getBroadcast(context, code, intent, flags)
        }
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_TOOK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_STOP_ID, wait.bus.stopId)
            .putExtra(EXTRA_VEHICLE_ID, wait.bus.bus.vehicleId)
            .putExtra(EXTRA_TRIP, wait.key.trip)
            .putExtra(EXTRA_STOP_NAME, wait.bus.stopName)
            .putExtra(EXTRA_LINE, wait.bus.bus.line)
            .putExtra(EXTRA_DESTINATION, wait.bus.bus.destination)
        return PendingIntent.getActivity(context, code, intent, flags)
    }

    /** The ride "I took it" names, from its intent */
    fun tookOf(intent: Intent): TookRide? {
        if (intent.action != ACTION_TOOK) return null
        val key = keyOf(intent) ?: return null
        return TookRide(
            line = intent.getStringExtra(EXTRA_LINE) ?: return null,
            destination = intent.getStringExtra(EXTRA_DESTINATION).orEmpty(),
            stopId = key.stopId,
            stopName = intent.getStringExtra(EXTRA_STOP_NAME).orEmpty(),
            vehicleId = key.vehicleId,
            trip = key.trip,
        )
    }

    /** Apart from the stop each notification opens, by its id */
    private const val TOOK_REQUEST_OFFSET = 1000

    /** Apart from one another, the "I took it" buttons of a stop's group */
    private const val TOOK_SLOT_STEP = 100

    private fun openStop(context: Context, id: Int, wait: Wait): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_STOP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_STOP_ID, wait.bus.stopId)
            // The stop's own name, as its details title it
            .putExtra(EXTRA_STOP_NAME, wait.bus.bus.stopName.ifEmpty { wait.bus.stopName })
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
