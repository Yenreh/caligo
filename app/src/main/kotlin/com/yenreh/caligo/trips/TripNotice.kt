package com.yenreh.caligo.trips

import android.content.res.Resources
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.domain.TripSource
import com.yenreh.caligo.waits.Alerts

/**
 * The app's notification channels for trips, apart from the waits' so
 * each can be set on its own in the system. Each is made once and never
 * altered, so what the user sets for it wins.
 */
enum class TripChannel(val id: String, val sound: Boolean, val vibrate: Boolean) {
    /** Every update: silent, never peeks */
    TRIPS("trips", sound = false, vibrate = false),

    /** The warning before the stop to get off at, as the settings ask */
    WARNING_SILENT("trip_warning_silent", sound = false, vibrate = false),
    WARNING_VIBRATE("trip_warning_vibrate", sound = false, vibrate = true),
    WARNING_SOUND("trip_warning_sound", sound = true, vibrate = false),
    WARNING_SOUND_VIBRATE("trip_warning_sound_vibrate", sound = true, vibrate = true),
    ;

    companion object {
        /** The warning channel matching [alerts] */
        fun warning(alerts: Alerts): TripChannel = when {
            alerts.sound && alerts.vibrate -> WARNING_SOUND_VIBRATE
            alerts.sound -> WARNING_SOUND
            alerts.vibrate -> WARNING_VIBRATE
            else -> WARNING_SILENT
        }
    }
}

/** A point of the notification's bar: a stop ahead, at [position] of the bar's length */
data class TripPoint(val position: Int)

/**
 * A trip's notification as it reads at a moment: a pure function of the
 * trip, the time and the alert settings, built into a notification by
 * [TripNotifications].
 */
data class TripNotice(
    val title: String,
    val text: String,
    /** Title and text in one line, for screen readers and the ticker */
    val summary: String,
    /** The status bar chip of a Live Update; null leaves none */
    val chip: String?,
    /** A chronometer counts down to this, epoch milliseconds; null shows none */
    val countdownTo: Long?,
    /** Metres ridden from the boarding stop, of [progressMax] to where the ride ends */
    val progress: Int,
    val progressMax: Int,
    /** The next stops ahead, at most [MAX_POINTS], before where the ride ends */
    val points: List<TripPoint>,
    val channel: TripChannel,
    /** This post is the warning: it may sound, vibrate and peek */
    val alerting: Boolean,
    /** Cancelled by the system after this many milliseconds unless posted again */
    val timeoutAfter: Long,
) {
    companion object {
        /**
         * The notice of [trip] at [now], with [alerts] for the warning. Once
         * warned, it stays on the warning channel, silent from then on; the
         * next post comes within [nextPostIn] milliseconds. With that
         * channel turned off, [warningOn] false, there is no warning and
         * every post stays on the trips' channel.
         */
        fun of(trip: Trip, now: Long, alerts: Alerts, words: TripWords, nextPostIn: Long, warningOn: Boolean = true): TripNotice {
            val plan = trip.plan
            val progress = trip.progress(now)
            val estimated = progress.source == TripSource.ESTIMATE
            val target = plan.stops[plan.target]
            val toTarget = trip.millisTo(plan.target, now)
            val minutes = trip.minutes(toTarget)
            val left = progress.stopsLeft
            val next = (progress.stop + 1).coerceAtMost(plan.target)
            val title: String
            val text: String
            val chip: String
            if (plan.alight != null) {
                when (left) {
                    0 -> {
                        title = words.getOffHere(plan.line, target.name)
                        text = words.atStopText
                        chip = words.chipHere
                    }
                    1 -> {
                        title = words.getOffNext(plan.line, target.name)
                        text = words.inMinutes(minutes, estimated)
                        chip = words.chipNext
                    }
                    else -> {
                        title = words.getOffIn(plan.line, target.name, minutes, estimated, left)
                        text = words.nextStop(plan.stops[next].name)
                        chip = words.chipMinutes(minutes, estimated)
                    }
                }
            } else {
                val toNext = trip.minutes(trip.millisTo(next, now))
                title = if (left == 0) words.atEnd(plan.line, target.name) else words.nextIn(plan.line, plan.stops[next].name, toNext, estimated)
                text = words.endsAt(target.name, minutes, estimated)
                chip = if (left == 0) words.chipHere else words.chipMinutes(minutes, estimated)
            }
            val shownText = if (trip.offRouteSince != null) words.offRoute else text
            val start = trip.route.at[plan.board]
            val length = (trip.route.at[plan.target] - start).coerceAtLeast(1.0)
            val points = (progress.stop + 1 until plan.target)
                .take(MAX_POINTS)
                .map { TripPoint((trip.route.at[it] - start).toInt()) }
            val alerting = warningOn && trip.warningDue(now) != null
            return TripNotice(
                title = title,
                text = shownText,
                summary = "$title, $shownText",
                chip = chip,
                countdownTo = if (toTarget > 0 && left > 0) now + toTarget else null,
                progress = (progress.along - start).coerceIn(0.0, length).toInt(),
                progressMax = length.toInt().coerceAtLeast(1),
                points = points,
                channel = if (warningOn && (alerting || trip.alerted)) TripChannel.warning(alerts) else TripChannel.TRIPS,
                alerting = alerting,
                timeoutAfter = nextPostIn + TIMEOUT_MARGIN_MS,
            )
        }

        /**
         * Points the system draws on a progress bar: Android's ProgressStyle
         * keeps 4 (MAX_PROGRESS_POINT_LIMIT in its Notification.java), so
         * the next four stops show, the rest as the ride goes on
         */
        const val MAX_POINTS = 4

        /** Beyond the gap to the next post: a process killed leaves its notification this long at most */
        const val TIMEOUT_MARGIN_MS = 30_000L
    }
}

/** The words of a trip's notification, in the app's language */
class TripWords(private val resources: Resources) {
    private fun about(estimated: Boolean) = if (estimated) "~" else ""

    fun getOffIn(line: String, stop: String, minutes: Int, estimated: Boolean, stops: Int) = resources.getQuantityString(
        R.plurals.trip_get_off_in,
        stops,
        line,
        stop,
        about(estimated) + minutes,
        stops,
    )

    fun getOffNext(line: String, stop: String) = resources.getString(R.string.trip_get_off_next, line, stop)

    fun getOffHere(line: String, stop: String) = resources.getString(R.string.trip_get_off_here, line, stop)

    fun nextIn(line: String, stop: String, minutes: Int, estimated: Boolean) =
        resources.getString(R.string.trip_next_in, line, stop, about(estimated) + minutes)

    fun atEnd(line: String, stop: String) = resources.getString(R.string.trip_at_end, line, stop)

    fun endsAt(stop: String, minutes: Int, estimated: Boolean) =
        resources.getString(R.string.trip_ends_at, stop, about(estimated) + minutes)

    fun nextStop(stop: String) = resources.getString(R.string.trip_next_stop, stop)

    fun inMinutes(minutes: Int, estimated: Boolean) = resources.getString(R.string.trip_in_minutes, about(estimated) + minutes)

    val atStopText: String get() = resources.getString(R.string.trip_at_stop_text)

    val offRoute: String get() = resources.getString(R.string.trip_off_route)

    fun chipMinutes(minutes: Int, estimated: Boolean) = resources.getString(R.string.trip_chip_minutes, about(estimated) + minutes)

    val chipNext: String get() = resources.getString(R.string.trip_chip_next)

    val chipHere: String get() = resources.getString(R.string.trip_chip_here)

    val finish: String get() = resources.getString(R.string.trip_finish)
}
