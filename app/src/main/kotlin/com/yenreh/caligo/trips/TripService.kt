package com.yenreh.caligo.trips

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.ServiceWakeLock
import com.yenreh.caligo.channelOn
import com.yenreh.caligo.domain.AskPoint
import com.yenreh.caligo.domain.AwaitedBus
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.TookRide
import com.yenreh.caligo.domain.WaitKey
import com.yenreh.caligo.domain.RunningWait
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.domain.WaitAnswer
import com.yenreh.caligo.domain.WaitEnding
import com.yenreh.caligo.domain.WaitStatus
import com.yenreh.caligo.waits.WaitChannel
import com.yenreh.caligo.waits.WaitDesk
import com.yenreh.caligo.waits.WaitEngine
import com.yenreh.caligo.waits.WaitHost
import com.yenreh.caligo.waits.WaitNotifications
import com.yenreh.caligo.waits.WaitStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The one owner of the trip and of its notification, a foreground
 * service of type location: started from the trip screen with the app on
 * show, so the while-in-use permission lets it read the location until
 * the trip ends, and no background location is ever asked for.
 *
 * It never runs without its notification: it starts as the trip is shown
 * and stops as it ends. Not sticky: killed, it does not come back, and
 * what it showed goes with the notification's timeout, or at the next
 * process start. A trip that ends by itself leaves a last word, not
 * ongoing, that goes by its own timeout, a tap or the next start.
 *
 * Finish leaves the trip running for [TripEngine.UNDO_WINDOW] under a
 * notification of its own, "Trip finished" with Undo, silent and gone by
 * itself soon after: a swipe of it, or the time running out, ends the
 * trip as finished, and nothing of it is left after that.
 *
 * Apart from the wait service, with ids and channels of its own, but for
 * the alert of an awaited bus, on the waits' alert channel as a wait's: the
 * rider's own waits keep their notifications and foreground state there,
 * and a trip that ends leaves them running, as they leave it. The waits a
 * planner trip starts for its rides run here instead, on a wait engine of
 * their own, with no notification of their own: the trip's carries them,
 * already in the foreground, so it shows them from the background too, as
 * after a change with the screen off, where a second foreground service
 * would not be let start. They end with the trip.
 *
 * A partial wake lock keeps the bus rounds and the beat on time with the
 * screen off, as the wait service's does: re-armed at every post, it lets
 * go by itself if anything stalls, and is released as the trip ends.
 */
class TripService : Service(), TripHost {
    private lateinit var desk: TripDesk
    private lateinit var scope: CoroutineScope
    private lateinit var engine: TripEngine
    private lateinit var words: TripWords
    private lateinit var waits: WaitDesk
    private lateinit var waitEngine: WaitEngine
    private val wakeLock = ServiceWakeLock(this, WAKE_LOCK_TAG)

    private val endWaitFromDesk: (WaitKey, WaitEnding) -> Unit = { key, ending -> endWait(key, ending) }

    private var lastStartId = 0

    /** The trip last shown, by when it started: one that ends before it ever was says nothing */
    private var shownStartedAt: Long? = null

    /** What was last posted, and when: the same again within [TripNotice.REPOST_AFTER] is left out */
    private var lastPosted: Any? = null
    private var lastPostedAt = 0L

    /** The actions of the trip on show, made once for it */
    private var actions: TripActions? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as CaliGoApp).container
        desk = container.trips
        waits = container.waits
        scope = CoroutineScope(SupervisorJob() + desk.coroutineContext)
        engine = TripEngine(desk.source, desk.location, desk.now, scope, this, desk.learning)
        waitEngine = WaitEngine(waits.source, desk.now, scope, tripWaits)
        words = TripWords(resources)
        TripNotifications.createChannels(this)
        waits.attachTripWaits(endWaitFromDesk)
        desk.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        desk.attach(this)
        val start = desk.takePending()
        // Started as a foreground service: it must say so at once
        if (start == null && engine.journey == null) {
            if (foreground(TripNotifications.PLACEHOLDER_ID, TripNotifications.placeholder(this))) idle() else stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (start != null) {
            // In the foreground before anything runs: on the main thread's
            // immediate dispatcher the trip's first round runs inside
            // start, and may end it there
            val now = desk.now()
            val journey = start.journey(now)
            if (!foreground(TripNotifications.TRIP_ID, checkNotNull(notification(journey, now, TripEngine.BEAT.inWholeMilliseconds)))) {
                // Not allowed to follow the location: nothing runs, and the screen hears why
                if (engine.journey != null) engine.end(TripEnding.NO_LOCATION) else idle()
                desk.endedAs(journey, TripEnding.NO_LOCATION)
                return START_NOT_STICKY
            }
            // Ended already, or running: either way the engine has seen to it
            engine.start(start)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        desk.location.stop()
        val manager = NotificationManagerCompat.from(this)
        manager.cancel(TripNotifications.TRIP_ID)
        // Killed with a trip on: the waits it started go with it
        waits.detachTripWaits(endWaitFromDesk)
        desk.awaitedChanged(emptyList())
        desk.rideBusesChanged(null)
        wakeLock.release()
        desk.detach(this)
        super.onDestroy()
    }

    internal fun start(start: TripStart) = engine.start(start)

    internal fun took(took: TookRide): Boolean = engine.took(took)

    internal fun alightAt(alight: Int?) = engine.alightAt(alight)

    internal fun end(ending: TripEnding) = engine.end(ending)

    /** Finish, which can be undone for a while */
    internal fun finish() = engine.finish()

    internal fun undo() = engine.undo()

    internal fun takeLine(line: String) = engine.takeLine(line)

    internal fun chooseLine(line: String?) = engine.chooseLine(line)

    /** A trip wait's bell tapped on a row: the rider stops awaiting that bus, and its ride is not asked for again */
    internal fun endWait(key: WaitKey, ending: WaitEnding) {
        if (ending != WaitEnding.TOOK) engine.waitDeclined()
        waitEngine.end(key, ending)
    }

    /** The trip's wait engine, for tests to see that nothing runs */
    internal val tripWaitEngine: WaitEngine get() = waitEngine

    /** The engine, for tests to see that nothing runs */
    internal val tripEngine: TripEngine get() = engine

    override fun allowed(): Boolean = TripNotifications.allowed(this)

    /**
     * [journey]'s notification at [now]; null when it would be what was
     * last posted, under [TripNotice.REPOST_AFTER] ago, and [same] may be
     * left out. Its timeout covers the latest post to come after it.
     */
    private fun notification(journey: Journey, now: Long, nextPostIn: Long, same: Boolean = false): Notification? {
        val actions = actions?.takeIf { it.startedAt == journey.startedAt } ?: TripActions(this, journey.startedAt).also { actions = it }
        // Finishing: what Undo takes back, and nothing that alerts
        val until = engine.finishingUntil.takeIf { journey.startedAt == engine.journey?.startedAt }
        if (until != null) {
            val notice = FinishingNotice.of(journey, until, now, words)
            if (posted(journey.startedAt to notice.copy(timeoutAfter = 0), now) && same) return null
            return TripNotifications.finishing(this, journey.startedAt, notice, words, actions)
        }
        val alerts = desk.alerts()
        val warningOn = channelOn(this, TripChannel.warning(alerts))
        val awaited = awaitedLines(journey, now)
        val busAlertOn = channelOn(this, WaitChannel.alert(alerts))
        val notice = TripNotice.of(journey, now, alerts, words, nextPostIn, warningOn, awaited, journey.nearBoarding(now), busAlertOn)
        if (posted(Triple(journey.startedAt, notice.copy(timeoutAfter = 0), alerts), now) && same) return null
        // Once a ride, whoever posts first
        if (notice.alerting && notice.busChannel != null && journey.startedAt == engine.journey?.startedAt) engine.busAlerted()
        return TripNotifications.build(this, journey.startedAt, notice, alerts, words, actions)
    }

    /** Whether [content] was posted last, under [TripNotice.REPOST_AFTER] before [now]; else it is taken as posted now */
    private fun posted(content: Any, now: Long): Boolean {
        if (content == lastPosted && now - lastPostedAt < REPOST_AFTER_MS) return true
        lastPosted = content
        lastPostedAt = now
        return false
    }

    /** The buses the trip awaits for the ride ahead, as their waits read now, soonest first */
    private fun awaitedLines(journey: Journey, now: Long): List<AwaitedLine> = waitEngine.waits
        .filter { it.key in journey.awaited }
        .mapNotNull { wait ->
            when (val status = wait.status(now)) {
                is WaitStatus.Coming -> AwaitedLine(wait.last.line, status.arrivalTime)
                WaitStatus.AtStop -> AwaitedLine(wait.last.line, wait.last.arrivalTime, atStop = true)
                else -> null
            }
        }
        .groupBy { it.line }
        .map { (_, buses) -> buses.minBy { it.dueAt(now) } }
        .sortedBy { it.dueAt(now) }

    // allowed() holds before every post: the app's notifications are on
    @SuppressLint("MissingPermission")
    override fun show(journey: Journey, now: Long, nextPostIn: Long) {
        shownStartedAt = journey.startedAt
        // The same as on show: not posted again, the beat still kept awake
        notification(journey, now, nextPostIn, same = true)?.let {
            posts++
            NotificationManagerCompat.from(this).notify(TripNotifications.TRIP_ID, it)
        }
        wakeLock.hold(nextPostIn + WAKE_MARGIN_MS)
    }

    // allowed() holds before the last word: the app's notifications are on
    @SuppressLint("MissingPermission")
    override fun ended(journey: Journey, ending: TripEnding) {
        desk.endedAs(journey, ending)
        val manager = NotificationManagerCompat.from(this)
        manager.cancel(TripNotifications.TRIP_ID)
        // Ended by itself once under way, the screen likely off: the rider
        // hears it. Not one that ends as it starts, the screen on show.
        if (journey.startedAt != shownStartedAt || !allowed()) return
        val alerts = desk.alerts()
        val warningOn = channelOn(this, TripChannel.warning(alerts))
        val notice = EndedNotice.of(journey, ending, alerts, words, warningOn) ?: return
        manager.notify(TripNotifications.ENDED_ID, TripNotifications.ended(this, notice, alerts))
    }

    override fun idle() {
        // Nothing of a trip outlives it: its waits, had any been left, go too
        if (waitEngine.waits.isNotEmpty()) waitEngine.endAll(WaitEnding.STOPPED)
        desk.awaitedChanged(emptyList())
        desk.rideBusesChanged(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val manager = NotificationManagerCompat.from(this)
        manager.cancel(TripNotifications.TRIP_ID)
        manager.cancel(TripNotifications.PLACEHOLDER_ID)
        desk.location.stop()
        wakeLock.release()
        desk.detach(this)
        // A start that came meanwhile keeps it running
        stopSelfResult(lastStartId)
    }

    override fun changed(journey: Journey?, finishingUntil: Long?) {
        desk.changed(journey, finishingUntil)
    }

    // Waits as the rider's own, each with its notification and "I took it", run here
    override fun startWaits(buses: List<AwaitedBus>): List<WaitKey> {
        WaitNotifications.createChannels(this)
        // A bus the rider already waits for keeps its own wait
        val theirs = waits.running.value
        return buses.filter { bus -> theirs.none { it.key == bus.key } && waitEngine.start(bus, null) == WaitStart.STARTED }.map { it.key }
    }

    override fun stopWaits(keys: List<WaitKey>) {
        // The trip's own doing, not the rider's: nothing declined
        for (key in keys) waitEngine.end(key, WaitEnding.STOPPED)
    }

    override fun runningWaits(): Set<WaitKey> = waitEngine.waits.mapTo(HashSet()) { it.key }

    override fun awaitedLine(key: WaitKey): String? = waitEngine.waits.firstOrNull { it.key == key }?.last?.line

    override fun rideBusesChanged(buses: RideBuses?) {
        desk.rideBusesChanged(buses)
    }

    override fun soonestAwaited(now: Long): Long? = engine.journey?.let { awaitedLines(it, now).firstOrNull()?.dueAt(now) }

    /**
     * What the trip's wait engine asks of the one that shows its waits:
     * here, where the trip's own notification carries them, soonest first
     */
    private val tripWaits = object : WaitHost {
        // The trip's own notification carries them, and the trip asks for its own channel
        override fun allowed(): Boolean = true

        // The trip's own notification carries them: nothing of their own
        override fun show(waits: List<Wait>, now: Long, nextPostIn: Long) {}

        override fun ended(wait: Wait, ending: WaitEnding) {}

        // What its stop's answers list of every line: the trip screen's rows
        override fun answered(point: AskPoint, answer: WaitAnswer) {
            engine.heard(point, answer)
        }

        override fun idle() {}

        override fun changed(waits: List<Wait>) {
            this@TripService.waits.tripWaitsChanged(waits.map { RunningWait(it.key, it.last.arrivalTime, it.listedAt) })
            engine.journey?.let { desk.awaitedChanged(awaitedLines(it, desk.now())) }
            // The trip's notice carries them: shown again as they change
            engine.refresh()
        }
    }

    /** In the foreground with [notification], as a location service; false when the system refuses */
    private fun foreground(id: Int, notification: Notification): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        return try {
            ServiceCompat.startForeground(this, id, notification, type)
            true
        } catch (e: RuntimeException) {
            // A SecurityException without the location permission, or, on
            // Android 12 and later, ForegroundServiceStartNotAllowedException
            // from the background: both are RuntimeExceptions
            if (e is SecurityException || e is IllegalStateException) false else throw e
        }
    }

    internal val holdsWakeLock: Boolean get() = wakeLock.isHeld

    /** How many times the trip's notification was posted, for tests to count */
    internal var posts = 0
        private set

    companion object {
        /** How long past the next post the wake lock holds, should that post be late */
        const val WAKE_MARGIN_MS = 30_000L

        private const val WAKE_LOCK_TAG = "CaliGo:trip"

        private val REPOST_AFTER_MS = TripNotice.REPOST_AFTER.inWholeMilliseconds
    }
}
