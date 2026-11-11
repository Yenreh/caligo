package com.yenreh.caligo.trips

import android.content.res.Resources
import com.yenreh.caligo.AppChannel
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.JourneyLeg
import com.yenreh.caligo.domain.JourneyPhase
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.waits.WaitChannel
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.domain.TripPlan
import com.yenreh.caligo.domain.TripSource
import com.yenreh.caligo.waits.Alerts

/**
 * The app's notification channels for trips, as [AppChannel] makes them:
 * apart from the waits' so each can be set on its own in the system
 */
enum class TripChannel(
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
     * peeks. It replaces the first, low "trips".
     */
    TRIPS("trip_updates", R.string.trip_channel, R.string.trip_channel_description, sound = false, vibrate = false, replaces = "trips", urgent = false),

    /** The warning before the stop to get off at, as the settings ask */
    WARNING_SILENT("trip_warning_silent", R.string.trip_warning_channel_silent, R.string.trip_warning_channel_description, sound = false, vibrate = false),
    WARNING_VIBRATE("trip_warning_vibrate", R.string.trip_warning_channel_vibrate, R.string.trip_warning_channel_description, sound = false, vibrate = true),
    WARNING_SOUND("trip_warning_sound", R.string.trip_warning_channel_sound, R.string.trip_warning_channel_description, sound = true, vibrate = false),
    WARNING_SOUND_VIBRATE("trip_warning_sound_vibrate", R.string.trip_warning_channel_sound_vibrate, R.string.trip_warning_channel_description, sound = true, vibrate = true),
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

/** A point of the notification's bar: one of the last stops before where the ride ends, at [position] of the bar's length */
data class TripPoint(val position: Int)

/** What moves along the notification's bar */
enum class TripTracker {
    /** On the bus, and waiting for it */
    BUS,

    /** On foot */
    WALKER,
}

/**
 * The last word of a trip that ended by itself, the screen likely off:
 * on the warning channel, so it peeks as the alerts are set, while the
 * rider may still count on a warning for a stop to get off at; otherwise
 * silent. A pure function of the trip and how it ended, built into a
 * notification by [TripNotifications].
 */
data class EndedNotice(val title: String, val text: String, val channel: TripChannel, val alerting: Boolean) {
    companion object {
        /** The endings the rider did not ask for nor see coming: the rest say nothing */
        val NOTICED = setOf(TripEnding.OFF_ROUTE, TripEnding.NO_LOCATION, TripEnding.TIME_LIMIT, TripEnding.LEFT_STOP, TripEnding.NO_BUS)

        /**
         * The notice of [journey] ended as [ending], or null for an ending
         * that says nothing. With the warning channel off, [warningOn]
         * false, it stays silent. A rider gone from the stop took no bus
         * of the trip: nothing to warn of.
         */
        fun of(journey: Journey, ending: TripEnding, alerts: Alerts, words: TripWords, warningOn: Boolean = true): EndedNotice? {
            if (ending !in NOTICED) return null
            val pending = journey.stopPending && ending != TripEnding.LEFT_STOP
            val alerting = pending && warningOn
            return EndedNotice(
                title = if (pending) words.endedWatch else words.ended,
                text = words.endedReason(ending),
                channel = if (alerting) TripChannel.warning(alerts) else TripChannel.TRIPS,
                alerting = alerting,
            )
        }
    }
}

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
    /** How far along the bar, of [progressMax]: on the bus, metres ridden from the boarding stop to where the ride ends */
    val progress: Int,
    val progressMax: Int,
    /** On the bus, the last stops before where the ride ends, at most [MAX_POINTS], fixed for the ride */
    val points: List<TripPoint>,
    val tracker: TripTracker,
    val channel: TripChannel,
    /** This post is the warning, or the alert of an awaited bus: it may sound, vibrate and peek */
    val alerting: Boolean,
    /** Cancelled by the system after this many milliseconds unless posted again */
    val timeoutAfter: Long,
    /** Lines to take with a button each, "I took the A47", soonest first */
    val takeLines: List<String> = emptyList(),
    /** The waits' alert channel, in place of [channel], from the alert of an awaited bus on for the rest of its ride */
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
         * of the first [MAX_LINE_ACTIONS] once [near] the stop; the alert as
         * the soonest comes within [BUS_ALERT_WITHIN], once a ride, on the
         * waits' alert channel as the settings ask, none with that channel
         * turned off, [busAlertOn] false.
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
            var progress = 0
            var progressMax = 1
            var tracker = TripTracker.BUS
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
                        // As at the stop: the buses awaited soonest first, or the lines to take
                        text = if (soonest.isEmpty()) {
                            "${phase.leg.toName} · ${words.then(words.lines(next.ride.lines))}"
                        } else {
                            "${phase.leg.toName} · " + soonest.joinToString(" · ") { words.lineMinutes(it, now) }
                        }
                    } else {
                        title = words.walkToDestination(minutes, phase.estimated)
                        text = phase.leg.toName
                    }
                    chip = words.chipMinutes(minutes, phase.estimated)
                    tracker = TripTracker.WALKER
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
                progress = progress,
                progressMax = progressMax,
                points = emptyList(),
                tracker = tracker,
                channel = TripChannel.TRIPS,
                alerting = alerting,
                timeoutAfter = nextPostIn + TIMEOUT_MARGIN_MS,
                takeLines = lines.distinct().take(MAX_LINE_ACTIONS),
                busChannel = if (busAlertOn && (alerting || alerted)) WaitChannel.alert(alerts) else null,
            )
        }

        /** A bus due within the minute still shows a bar worth reading */
        private const val MIN_WAIT_BAR_SECONDS = 60L

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
            val minutes = trip.minutesTo(plan.target, now)
            val left = progress.stopsLeft
            val next = (progress.stop + 1).coerceAtMost(plan.target)
            val title: String
            val text: String
            val chip: String
            // Off the route, the text names the line where the title does not
            var lineInTitle = true
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
                // Where the rider gets off, the line below; following the line to its end, the bus's own destination
                plan.alight != null -> {
                    title = words.getOffAt(target.name, minutes, estimated)
                    lineInTitle = false
                    text = words.withLine(plan.line, words.stopsNext(left, plan.stops[next].name))
                    chip = words.chipMinutes(minutes, estimated)
                }
                else -> {
                    title = words.rideTo(plan.line, target.name, minutes, estimated)
                    text = words.stopsNext(left, plan.stops[next].name)
                    chip = words.chipMinutes(minutes, estimated)
                }
            }
            val shownText = when {
                trip.offRouteSince == null -> text
                lineInTitle -> words.offRoute
                else -> words.withLine(plan.line, words.offRoute)
            }
            val start = trip.route.at[plan.board]
            val length = (trip.route.at[plan.target] - start).coerceAtLeast(1.0)
            val progressMax = length.toInt().coerceAtLeast(1)
            val alerting = warningOn && trip.warningDue(now) != null
            return TripNotice(
                title = title,
                text = shownText,
                summary = "$title, $shownText",
                chip = chip,
                progress = (progress.along - start).coerceIn(0.0, length).toInt(),
                progressMax = progressMax,
                points = lastStops(trip.route.at, plan.board, plan.target, progressMax),
                tracker = TripTracker.BUS,
                channel = if (warningOn && (alerting || trip.alerted)) TripChannel.warning(alerts) else TripChannel.TRIPS,
                alerting = alerting,
                timeoutAfter = nextPostIn + TIMEOUT_MARGIN_MS,
            )
        }

        /**
         * Points the system draws on a progress bar: Android's ProgressStyle
         * keeps the first 4 (MAX_PROGRESS_POINT_LIMIT in its
         * Notification.java, API 36), so the last four stops before the
         * one to get off at show
         */
        const val MAX_POINTS = 4

        /**
         * The stops just before [target], at most [MAX_POINTS], at their
         * metres from [board] on a bar [progressMax] long, with stops at
         * [at]. The same for the whole ride, the passed ones styled by the
         * progress. Android drops a point at either end, so none goes
         * there, nor one where the point before it is, as two berths of a
         * stop.
         */
        internal fun lastStops(at: DoubleArray, board: Int, target: Int, progressMax: Int): List<TripPoint> {
            val start = at[board]
            val points = ArrayList<TripPoint>(MAX_POINTS)
            for (i in maxOf(board + 1, target - MAX_POINTS) until target) {
                val position = (at[i] - start).toInt()
                if (position >= progressMax) continue
                if (position <= (points.lastOrNull()?.position ?: 0)) continue
                points.add(TripPoint(position))
            }
            return points
        }

        /** Beyond the gap to the next post: a process killed leaves its notification this long at most */
        const val TIMEOUT_MARGIN_MS = 30_000L
    }
}

/** The words of a trip, on its notification and its screens, in the app's language */
class TripWords(private val resources: Resources) {
    private fun about(estimated: Boolean) = if (estimated) "~" else ""

    /** "E21 to Universidades, 13 min": the bus's own destination */
    fun rideTo(line: String, stop: String, minutes: Int, estimated: Boolean) =
        resources.getString(R.string.trip_ride_to, line, stop, about(estimated) + minutes)

    fun getOffAt(stop: String, minutes: Int, estimated: Boolean) =
        resources.getString(R.string.trip_get_off_at, stop, about(estimated) + minutes)

    /** "E21 · [text]" */
    fun withLine(line: String, text: String) = resources.getString(R.string.trip_with_line, line, text)

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

    val ended: String get() = resources.getString(R.string.trip_ended_title)

    val endedWatch: String get() = resources.getString(R.string.trip_ended_title_watch)

    /** Why a trip ended by itself, as the trip screen says it */
    fun endedReason(ending: TripEnding): String = when (ending) {
        TripEnding.TIME_LIMIT -> resources.getString(R.string.trip_ended_time_limit)
        TripEnding.OFF_ROUTE -> resources.getString(R.string.trip_ended_off_route)
        TripEnding.NO_LOCATION -> resources.getString(R.string.trip_ended_no_location)
        TripEnding.LEFT_STOP -> resources.getString(R.string.trip_ended_left_stop)
        TripEnding.NO_BUS -> resources.getString(R.string.trip_ended_no_bus, Journey.WAITING_LIMIT.inWholeMinutes.toInt())
        TripEnding.BLOCKED -> resources.getString(R.string.trip_ended_blocked)
        TripEnding.DISMISSED -> resources.getString(R.string.trip_ended_dismissed)
        TripEnding.REPLACED -> resources.getString(R.string.trip_ended_replaced)
        TripEnding.REACHED, TripEnding.FINISHED -> resources.getString(R.string.trip_ended_finished)
    }

    /** "Get off at Universidades", "You are at your stop", or "Following the ride to Universidades": a ride with [stopsLeft] to where it ends */
    fun rideHeading(plan: TripPlan, stopsLeft: Int): String = when {
        plan.alight != null && stopsLeft == 0 -> resources.getString(R.string.trip_heading_here)
        plan.alight != null -> resources.getString(R.string.trip_heading_get_off, plan.stops[plan.target].name)
        else -> resources.getString(R.string.trip_heading_follow, plan.stops[plan.target].name)
    }

    /** "13 min · 5 stops" */
    fun headingDetail(minutes: Int, estimated: Boolean, stops: Int) =
        resources.getQuantityString(R.plurals.trip_heading_detail, stops, about(estimated) + minutes, stops)

    /** "13 min", "~13 min" */
    fun minutes(minutes: Int, estimated: Boolean) = resources.getString(R.string.trip_minutes, about(estimated) + minutes)

    /** "4 min · 300 m" */
    fun walkDetail(minutes: Int, estimated: Boolean, meters: Int) = resources.getString(R.string.trip_walk_detail, about(estimated) + minutes, meters)

    /** "Walk to Plaza de Cayzedo A1, 4 min" */
    fun walkTo(place: String, minutes: Int, estimated: Boolean) = resources.getString(R.string.trip_walk_to, place, about(estimated) + minutes)

    fun walkToStop(minutes: Int, estimated: Boolean) = resources.getString(R.string.trip_walk_to_stop, about(estimated) + minutes)

    fun walkToDestination(minutes: Int, estimated: Boolean) =
        resources.getString(R.string.trip_walk_to_destination, about(estimated) + minutes)

    /** "then the E21 or T31", after the stop on the notification */
    fun then(lines: String) = resources.getString(R.string.trip_then, lines)

    /** "Then the E21 or T31", a line of its own on the screen */
    fun thenTake(lines: String) = resources.getString(R.string.trip_then_take, lines)

    fun take(lines: String) = resources.getString(R.string.trip_heading_take, lines)

    fun atStop(stop: String) = resources.getString(R.string.trip_at_stop, stop)

    /** "A47 3 min", on the way to the stop and at it */
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
