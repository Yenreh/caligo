package com.yenreh.caligo.waits

import android.content.res.Resources
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.domain.WaitStatus

/** How the alert near the stop sounds, as the settings' "Alerts" ask */
data class Alerts(val sound: Boolean = false, val vibrate: Boolean = false)

/**
 * The app's notification channels for waits. Each is made once and never
 * altered, so what the user sets for it in the system wins.
 */
enum class WaitChannel(val id: String, val sound: Boolean, val vibrate: Boolean) {
    /** Every update: silent, never peeks */
    WAITS("waits", sound = false, vibrate = false),

    /** The alert near the stop, as the settings ask */
    ALERT_SILENT("alert_silent", sound = false, vibrate = false),
    ALERT_VIBRATE("alert_vibrate", sound = false, vibrate = true),
    ALERT_SOUND("alert_sound", sound = true, vibrate = false),
    ALERT_SOUND_VIBRATE("alert_sound_vibrate", sound = true, vibrate = true),
    ;

    companion object {
        /** The alert channel matching [alerts] */
        fun alert(alerts: Alerts): WaitChannel = when {
            alerts.sound && alerts.vibrate -> ALERT_SOUND_VIBRATE
            alerts.sound -> ALERT_SOUND
            alerts.vibrate -> ALERT_VIBRATE
            else -> ALERT_SILENT
        }
    }
}

/** What the buttons of a wait's notification do */
enum class WaitAction { TOOK, STOP }

/**
 * A wait's notification as it reads at a moment: a pure function of the
 * wait, the time and the alert settings, built into a notification by
 * [WaitNotifications].
 */
data class WaitNotice(
    val title: String,
    val text: String,
    /** Title and text in one line, for screen readers and the ticker */
    val summary: String,
    /** The status bar chip of a Live Update; null leaves none */
    val chip: String?,
    /** A chronometer counts down to this, epoch milliseconds; null shows none */
    val countdownTo: Long?,
    /** How far the wait has gone, of [progressMax] */
    val progress: Int,
    val progressMax: Int,
    val actions: List<WaitAction>,
    val channel: WaitChannel,
    /** This post is the alert near the stop: it may sound, vibrate and peek */
    val alerting: Boolean,
    /** Cancelled by the system after this many milliseconds unless posted again */
    val timeoutAfter: Long,
) {
    companion object {
        /**
         * The notice of [wait] at [now], with [alerts] for the alert. Once
         * alerted, it stays on the alert channel, silent from then on; the
         * next post comes within [nextPostIn] milliseconds. With that
         * channel turned off, [alertOn] false, there is no alert and every
         * post stays on the channel of updates: never on one that is off.
         */
        fun of(
            wait: Wait,
            now: Long,
            alerts: Alerts,
            words: WaitWords,
            nextPostIn: Long,
            alertOn: Boolean = true,
        ): WaitNotice {
            val bus = wait.last
            val status = wait.status(now)
            val alerting = alertOn && wait.alertDue(now)
            val title: String
            val text: String
            var chip: String? = null
            var countdownTo: Long? = null
            when (status) {
                is WaitStatus.Coming -> {
                    val minutes = bus.minutesUntilArrival(now)
                    title = if (minutes == 0) {
                        words.titleNow(bus.line, bus.destination)
                    } else {
                        words.titleMinutes(bus.line, bus.destination, minutes)
                    }
                    text = if (status.seen) wait.bus.stopName else words.notSeenNow(wait.bus.stopName)
                    chip = if (minutes == 0) words.chipNow else words.chipMinutes(minutes)
                    if (status.arrivalTime > now) countdownTo = status.arrivalTime
                }
                WaitStatus.AtStop -> {
                    title = words.titleAtStop(bus.line, bus.destination)
                    text = wait.bus.stopName
                    chip = words.chipHere
                }
                WaitStatus.NoData -> {
                    title = words.titleNoData(bus.line, bus.destination)
                    text = wait.bus.stopName
                }
                WaitStatus.Asking -> {
                    title = words.titleAsking(bus.line)
                    text = words.busAt(bus.line, bus.destination, wait.bus.stopName)
                }
                WaitStatus.NotSeen -> {
                    title = words.titleNotSeen
                    text = words.busAt(bus.line, bus.destination, wait.bus.stopName)
                }
                WaitStatus.NoDataEnded -> {
                    title = words.titleNoDataEnded
                    text = words.busAt(bus.line, bus.destination, wait.bus.stopName)
                }
            }
            // From the start to the bus at the stop
            val total = ((bus.arrivalTime - wait.startedAt) / 1000).coerceAtLeast(MIN_PROGRESS_SECONDS).toInt()
            val done = when (status) {
                WaitStatus.AtStop, WaitStatus.Asking -> total
                else -> ((now - wait.startedAt) / 1000).coerceIn(0, total.toLong()).toInt()
            }
            return WaitNotice(
                title = title,
                text = text,
                summary = "$title, $text",
                chip = chip,
                countdownTo = countdownTo,
                progress = done,
                progressMax = total,
                actions = listOf(WaitAction.TOOK, WaitAction.STOP),
                channel = if (alertOn && (alerting || wait.alerted)) WaitChannel.alert(alerts) else WaitChannel.WAITS,
                alerting = alerting,
                timeoutAfter = nextPostIn + TIMEOUT_MARGIN_MS,
            )
        }

        /** A wait due within the minute still shows a bar worth reading */
        private const val MIN_PROGRESS_SECONDS = 60L

        /**
         * Beyond the gap to the next post: a process killed without warning
         * leaves its notifications this long at most, and a post a little
         * late does not let one flicker away
         */
        const val TIMEOUT_MARGIN_MS = 30_000L
    }
}

/** The words of a wait's notification, in the app's language */
class WaitWords(private val resources: Resources) {
    fun titleMinutes(line: String, destination: String, minutes: Int) =
        resources.getString(R.string.wait_title_minutes, line, destination, minutes)

    fun titleNow(line: String, destination: String) = resources.getString(R.string.wait_title_now, line, destination)

    fun titleAtStop(line: String, destination: String) =
        resources.getString(R.string.wait_title_at_stop, line, destination)

    fun titleNoData(line: String, destination: String) =
        resources.getString(R.string.wait_title_no_data, line, destination)

    fun titleAsking(line: String) = resources.getString(R.string.wait_title_asking, line)

    val titleNotSeen: String get() = resources.getString(R.string.wait_title_not_seen)

    val titleNoDataEnded: String get() = resources.getString(R.string.wait_title_no_data_ended)

    fun notSeenNow(stop: String) = resources.getString(R.string.wait_not_seen_now, stop)

    fun busAt(line: String, destination: String, stop: String) =
        resources.getString(R.string.wait_bus_at, line, destination, stop)

    fun chipMinutes(minutes: Int) = resources.getString(R.string.wait_chip_minutes, minutes)

    val chipNow: String get() = resources.getString(R.string.wait_chip_now)

    val chipHere: String get() = resources.getString(R.string.wait_chip_here)

    fun action(action: WaitAction): String = resources.getString(
        when (action) {
            WaitAction.TOOK -> R.string.wait_took_it
            WaitAction.STOP -> R.string.wait_stop_waiting
        },
    )
}
