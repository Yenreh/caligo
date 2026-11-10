package com.yenreh.caligo.trips

import android.content.res.Resources
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.JourneyLeg
import com.yenreh.caligo.domain.JourneyPhase
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.waits.WaitChannel
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

/**
 * A bus the trip awaits for the ride ahead, as its wait reads it: due at
 * [arrivalTime], or [atStop]
 */
data class AwaitedLine(val line: String, val arrivalTime: Long, val atStop: Boolean = false) {
    fun dueAt(now: Long): Long = if (atStop) now else arrivalTime
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
    /** Lines to take with a button each, "I took the A47", soonest first */
    val takeLines: List<String> = emptyList(),
    /** The channel of the alert for an awaited bus, once due, in place of [channel] */
    val busChannel: WaitChannel? = null,
) {
    /** The channel it posts on */
    val channelId: String get() = busChannel?.id ?: channel.id

    companion object {
        /**
         * The notice of [journey] at [now]: on the bus, its ride's, as [of]
         * a ride; on foot, the walk left and what comes after it; at the
         * stop, the lines to take. The buses the trip awaits for the ride
         * ahead, [awaited], go in it soonest first, with a button for each
         * of the first [MAX_LINE_ACTIONS] once near the stop; the alert as
         * the soonest comes within [BUS_ALERT_WITHIN], once a ride, when
         * [busAlertDue].
         */
        fun of(
            journey: Journey,
            now: Long,
            alerts: Alerts,
            words: TripWords,
            nextPostIn: Long,
            warningOn: Boolean = true,
            awaited: List<AwaitedLine> = emptyList(),
            near: Boolean = false,
            busAlertOn: Boolean = true,
        ): TripNotice {
            val title: String
            val text: String
            var chip: String?
            var countdownTo: Long? = null
            var progress = 0
            var progressMax = 1
            val soonest = awaited.sortedBy { it.dueAt(now) }
            var lines = emptyList<String>()
            when (val phase = journey.phase(now)) {
                is JourneyPhase.Riding -> return of(phase.trip, now, alerts, words, nextPostIn, warningOn)
                is JourneyPhase.Walking -> {
                    val minutes = Trip.minutesOf(phase.millis)
                    val next = journey.legs.getOrNull(journey.index + 1) as? JourneyLeg.Ride
                    // Short titles, which a collapsed notification shows whole; the stop on the line below
                    if (phase.leg.toStop && next != null) {
                        title = words.walkToStop(minutes, phase.estimated)
                        val then = if (soonest.isEmpty()) {
                            words.lines(next.ride.lines)
                        } else {
                            words.lines(soonest.map { words.lineIn(it, now) })
                        }
                        text = "${phase.leg.toName} · ${words.then(then)}"
                    } else {
                        title = words.walkToDestination(minutes, phase.estimated)
                        text = phase.leg.toName
                    }
                    chip = words.chipMinutes(minutes, phase.estimated)
                    if (phase.millis > 0) countdownTo = now + phase.millis
                    val total = phase.leg.walk.meters.coerceAtLeast(1.0)
                    progressMax = total.toInt().coerceAtLeast(1)
                    progress = (total - phase.meters).coerceIn(0.0, total).toInt()
                    if (near && next != null) lines = soonest.map { it.line }
                }
                is JourneyPhase.Waiting -> {
                    // The soonest first, then the lines with no bus awaited
                    val ordered = (soonest.map { it.line } + phase.leg.ride.lines).distinct()
                    title = words.take(words.lines(ordered))
                    val stop = words.atStop(phase.leg.ride.board.name)
                    text = if (soonest.isEmpty()) "$stop · ${words.tookItHint}" else "$stop · " + soonest.joinToString(" · ") { words.lineMinutes(it, now) }
                    val first = soonest.firstOrNull()
                    chip = first?.let { if (it.atStop) words.chipHere else words.chipMinutes(Trip.minutesOf(it.dueAt(now) - now), estimated = false) }
                    countdownTo = first?.takeIf { !it.atStop && it.dueAt(now) > now }?.dueAt(now)
                    // From when the waiting began to the soonest bus, filling as it nears; empty with none awaited
                    if (first != null) {
                        val since = journey.waitingSince()
                        val total = ((first.dueAt(now) - since) / 1000).coerceAtLeast(MIN_WAIT_BAR_SECONDS)
                        progressMax = total.toInt()
                        progress = ((now - since) / 1000).coerceIn(0, total).toInt()
                    }
                    lines = ordered
                }
            }
            val alerting = busAlertOn && journey.pendingRide != null && journey.busAlertedFor != journey.pendingRide &&
                soonest.firstOrNull()?.let { it.atStop || it.dueAt(now) - now <= BUS_ALERT_WITHIN_MS } == true
            val alerted = journey.pendingRide != null && journey.busAlertedFor == journey.pendingRide
            return TripNotice(
                title = title,
                text = text,
                summary = "$title, $text",
                chip = chip,
                countdownTo = countdownTo,
                progress = progress,
                progressMax = progressMax,
                points = emptyList(),
                channel = TripChannel.TRIPS,
                alerting = alerting,
                timeoutAfter = nextPostIn + TIMEOUT_MARGIN_MS,
                takeLines = lines.distinct().take(MAX_LINE_ACTIONS),
                busChannel = if (busAlertOn && (alerting || alerted)) WaitChannel.alert(alerts) else null,
            )
        }

        /** A bus due within the minute still shows a bar worth reading */
        private const val MIN_WAIT_BAR_SECONDS = 60L

        /** When the waiting at the stop began: the ride's leg on, or the walk to it done by the estimate */
        private fun Journey.waitingSince(): Long = when (val on = leg) {
            is JourneyLeg.Walk -> legStartedAt + on.walk.duration.inWholeMilliseconds
            is JourneyLeg.Ride -> legStartedAt
        }

        /**
         * Lines with a button of their own: Android shows three actions at
         * most, and Finish takes one
         */
        const val MAX_LINE_ACTIONS = 2

        /** The awaited bus this close gives the alert, as a wait's does */
        val BUS_ALERT_WITHIN = Wait.ALERT_WITHIN
        private val BUS_ALERT_WITHIN_MS = BUS_ALERT_WITHIN.inWholeMilliseconds

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
            // Short titles, which a collapsed notification shows whole; the stops on the line below
            when {
                left == 0 -> {
                    title = if (plan.alight != null) words.getOffHere(plan.line) else words.endOfLine(plan.line)
                    text = target.name
                    chip = words.chipHere
                }
                left == 1 && plan.alight != null -> {
                    title = words.getOffNext(plan.line)
                    text = words.stopIn(target.name, minutes, estimated)
                    chip = words.chipNext
                }
                else -> {
                    title = words.rideTo(plan.line, target.name, minutes, estimated)
                    text = words.stopsNext(left, plan.stops[next].name)
                    chip = words.chipMinutes(minutes, estimated)
                }
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

    fun rideTo(line: String, stop: String, minutes: Int, estimated: Boolean) =
        resources.getString(R.string.trip_ride_to, line, stop, about(estimated) + minutes)

    fun stopsNext(stops: Int, next: String) = resources.getQuantityString(R.plurals.trip_stops_next, stops, stops, next)

    fun getOffNext(line: String) = resources.getString(R.string.trip_get_off_next, line)

    fun getOffHere(line: String) = resources.getString(R.string.trip_get_off_here, line)

    fun endOfLine(line: String) = resources.getString(R.string.trip_end_of_line, line)

    fun stopIn(stop: String, minutes: Int, estimated: Boolean) = resources.getString(R.string.trip_stop_in, stop, about(estimated) + minutes)

    val offRoute: String get() = resources.getString(R.string.trip_off_route)

    fun chipMinutes(minutes: Int, estimated: Boolean) = resources.getString(R.string.trip_chip_minutes, about(estimated) + minutes)

    val chipNext: String get() = resources.getString(R.string.trip_chip_next)

    val chipHere: String get() = resources.getString(R.string.trip_chip_here)

    val finish: String get() = resources.getString(R.string.trip_finish)

    fun walkToStop(minutes: Int, estimated: Boolean) = resources.getString(R.string.trip_walk_to_stop, about(estimated) + minutes)

    fun walkToDestination(minutes: Int, estimated: Boolean) =
        resources.getString(R.string.trip_walk_to_destination, about(estimated) + minutes)

    fun then(lines: String) = resources.getString(R.string.trip_then, lines)

    fun take(lines: String) = resources.getString(R.string.trip_heading_take, lines)

    fun atStop(stop: String) = resources.getString(R.string.trip_at_stop, stop)

    /** "A47 in 3 min", for the walk */
    fun lineIn(bus: AwaitedLine, now: Long) = if (bus.atStop) {
        resources.getString(R.string.trip_line_at_stop, bus.line)
    } else {
        resources.getString(R.string.trip_line_in, bus.line, Trip.minutesOf(bus.dueAt(now) - now))
    }

    /** "A47 3 min", at the stop */
    fun lineMinutes(bus: AwaitedLine, now: Long) = if (bus.atStop) {
        resources.getString(R.string.trip_line_at_stop, bus.line)
    } else {
        resources.getString(R.string.trip_line_minutes, bus.line, Trip.minutesOf(bus.dueAt(now) - now))
    }

    fun tookLine(line: String) = resources.getString(R.string.trip_took_line, line)

    val tookItHint: String get() = resources.getString(R.string.trip_took_it_hint)

    /** "E21", "E21 or T31", "E21, T31 or A47" */
    fun lines(lines: List<String>): String = when (lines.size) {
        0 -> ""
        1 -> lines.single()
        else -> resources.getString(R.string.trip_lines_or, lines.dropLast(1).joinToString(", "), lines.last())
    }
}
