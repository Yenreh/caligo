package com.yenreh.caligo.waits

import android.content.res.Resources
import com.yenreh.caligo.AppChannel
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.domain.WaitKey
import com.yenreh.caligo.domain.WaitStatus

/** How the alert near the stop sounds, as the settings' "Alerts" ask */
data class Alerts(val sound: Boolean = false, val vibrate: Boolean = false)

/** The app's notification channels for waits, as [AppChannel] makes them */
enum class WaitChannel(
    override val id: String,
    override val title: Int,
    override val description: Int,
    override val sound: Boolean,
    override val vibrate: Boolean,
    override val replaces: String? = null,
    override val urgent: Boolean = true,
) : AppChannel {
    /**
     * Every update: of default importance, so it shows on a lock screen
     * that hides silent notifications, yet never sounds, vibrates nor
     * peeks. It replaces 5.1's low "waits".
     */
    WAITS("wait_updates", R.string.wait_channel, R.string.wait_channel_description, sound = false, vibrate = false, replaces = "waits", urgent = false),

    /** The alert near the stop, as the settings ask */
    ALERT_SILENT("alert_silent", R.string.alert_channel_silent, R.string.alert_channel_description, sound = false, vibrate = false),
    ALERT_VIBRATE("alert_vibrate", R.string.alert_channel_vibrate, R.string.alert_channel_description, sound = false, vibrate = true),
    ALERT_SOUND("alert_sound", R.string.alert_channel_sound, R.string.alert_channel_description, sound = true, vibrate = false),
    ALERT_SOUND_VIBRATE("alert_sound_vibrate", R.string.alert_channel_sound_vibrate, R.string.alert_channel_description, sound = true, vibrate = true),
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
enum class WaitAction {
    TOOK,
    STOP,

    /** "No", the answer to "Did you take it?": stops waiting, as [STOP] */
    NOT_TAKEN,

    /** "Yes" to "Did you take the T31?" in a stop's group: that bus taken, as [TOOK] */
    YES,

    /** "Stop waiting" in a stop's group: every wait of it ends */
    STOP_ALL,
}

/** A button of a notification, for the wait [key] */
data class WaitButton(val action: WaitAction, val key: WaitKey, val line: String? = null)

/**
 * The stop a wait was started at as the rider saw it: the card or the
 * sheet whose bell started it, by the name it titles the stop with and
 * where it found it. A station card's platforms are one. Waits at one
 * share a notification.
 */
data class WaitGroup(val stopName: String, val anchor: GeoPoint)

val Wait.group: WaitGroup get() = WaitGroup(bus.stopName, bus.anchor)

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
    /** The buttons of a stop's group, each for its wait; empty for one wait alone, whose [actions] are its own */
    val buttons: List<WaitButton> = emptyList(),
) {
    companion object {
        /**
         * The notice of [waits], those at one stop as the rider saw it,
         * started in that order: one alone as [of] says; several as one
         * notification, their buses soonest first in its title, the stop
         * below, the chip and the bar the soonest's, an "I took it" for the
         * two soonest and "Stop waiting" for all. A bus that passed asks
         * "Did you take the T31?" for its minute, the earliest first, with
         * Yes, No and "Stop waiting". [alerted] are the waits whose alert
         * has gone out: one more alerts at most, once.
         */
        fun ofGroup(
            waits: List<Wait>,
            now: Long,
            alerts: Alerts,
            words: WaitWords,
            nextPostIn: Long,
            alertOn: Boolean = true,
            alerted: Set<WaitKey> = emptySet(),
        ): WaitNotice {
            if (waits.size == 1) {
                val wait = waits.single()
                return of(wait, now, alerts, words, nextPostIn, alertOn).let { if (it.alerting && wait.key in alerted) it.copy(alerting = false) else it }
            }
            val sorted = waits.sortedBy { it.last.arrivalTime }
            val stop = sorted.first().bus.stopName
            val asking = sorted.firstOrNull { it.status(now) == WaitStatus.Asking }
            // The soonest still to come shows its chip and bar, as one wait's notice does
            val soonest = sorted.firstOrNull { it.status(now) is WaitStatus.Coming || it.status(now) == WaitStatus.AtStop } ?: sorted.first()
            val own = of(soonest, now, alerts, words, nextPostIn, alertOn)
            val alertingWait = if (alertOn) sorted.firstOrNull { it.alertDue(now) && it.key !in alerted } else null
            val title = asking?.let { words.titleAsking(it.last.line) } ?: sorted.joinToString(" · ") { words.short(it, now) }
            val buttons = if (asking != null) {
                listOf(
                    WaitButton(WaitAction.YES, asking.key, asking.last.line),
                    WaitButton(WaitAction.NOT_TAKEN, asking.key, asking.last.line),
                    WaitButton(WaitAction.STOP_ALL, sorted.first().key),
                )
            } else {
                sorted.filter { it.asks(now) }.take(MAX_TOOK).map { WaitButton(WaitAction.TOOK, it.key, it.last.line) } +
                    WaitButton(WaitAction.STOP_ALL, sorted.first().key)
            }
            return WaitNotice(
                title = title,
                text = stop,
                summary = "$title, $stop",
                chip = own.chip,
                countdownTo = own.countdownTo,
                progress = own.progress,
                progressMax = own.progressMax,
                actions = emptyList(),
                channel = if (alertOn && (alertingWait != null || sorted.any { it.alerted })) WaitChannel.alert(alerts) else WaitChannel.WAITS,
                alerting = alertingWait != null,
                timeoutAfter = timeoutAfter(nextPostIn),
                buttons = buttons,
            )
        }

        /** "I took it" buttons of a stop's group, the soonest first: Android shows three actions, "Stop waiting" one */
        const val MAX_TOOK = 2

        /** A post the same as the last one is left out within this of it, as the trip's */
        const val REPOST_AFTER_MS = 20_000L

        /**
         * Gone by itself this long after it is posted: past the next post due
         * in [nextPostIn] milliseconds, or the latest should those be the
         * same and left out, the first beat past [REPOST_AFTER_MS], and
         * [TIMEOUT_MARGIN_MS] more
         */
        fun timeoutAfter(nextPostIn: Long): Long =
            maxOf(nextPostIn, REPOST_AFTER_MS + WaitEngine.BEAT.inWholeMilliseconds) + TIMEOUT_MARGIN_MS

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
            // From the start to the bus at the stop, in whole minutes as the title counts them, so the two move together
            val total = bus.minutesUntilArrival(wait.startedAt).coerceAtLeast(1)
            val done = when (status) {
                WaitStatus.AtStop, WaitStatus.Asking -> total
                else -> (total - bus.minutesUntilArrival(now)).coerceIn(0, total)
            }
            return WaitNotice(
                title = title,
                text = text,
                summary = "$title, $text",
                chip = chip,
                countdownTo = countdownTo,
                progress = done,
                progressMax = total,
                actions = listOf(
                    WaitAction.TOOK,
                    if (status == WaitStatus.Asking) WaitAction.NOT_TAKEN else WaitAction.STOP,
                ),
                channel = if (alertOn && (alerting || wait.alerted)) WaitChannel.alert(alerts) else WaitChannel.WAITS,
                alerting = alerting,
                timeoutAfter = timeoutAfter(nextPostIn),
            )
        }


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

    /** A wait in a stop's group, in short: "T31 4 min", "T31 at the stop", "T31 passed" */
    fun short(wait: Wait, now: Long): String {
        val line = wait.last.line
        return when (val status = wait.status(now)) {
            is WaitStatus.Coming -> {
                val minutes = wait.last.minutesUntilArrival(now)
                if (minutes == 0) resources.getString(R.string.wait_short_now, line) else resources.getString(R.string.wait_short_minutes, line, minutes)
            }
            WaitStatus.AtStop -> resources.getString(R.string.wait_short_at_stop, line)
            WaitStatus.Asking -> resources.getString(R.string.wait_short_passed, line)
            WaitStatus.NoData, WaitStatus.NoDataEnded -> resources.getString(R.string.wait_short_no_data, line)
            WaitStatus.NotSeen -> resources.getString(R.string.wait_short_not_seen, line)
        }
    }

    /** "T31: I took it" */
    fun tookLine(line: String) = resources.getString(R.string.wait_took_line, line)

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
            WaitAction.NOT_TAKEN -> R.string.wait_not_taken
            WaitAction.YES -> R.string.wait_yes
            WaitAction.STOP_ALL -> R.string.wait_stop_waiting
        },
    )
}
