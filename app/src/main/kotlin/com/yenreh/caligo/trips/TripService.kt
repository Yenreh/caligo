package com.yenreh.caligo.trips

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.domain.AwaitedBus
import com.yenreh.caligo.domain.Journey
import com.yenreh.caligo.domain.TookRide
import com.yenreh.caligo.domain.WaitKey
import com.yenreh.caligo.domain.RunningWait
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.domain.WaitEnding
import com.yenreh.caligo.waits.WaitChannel
import com.yenreh.caligo.waits.WaitDesk
import com.yenreh.caligo.waits.WaitEngine
import com.yenreh.caligo.waits.WaitHost
import com.yenreh.caligo.waits.WaitNotice
import com.yenreh.caligo.waits.WaitNotifications
import com.yenreh.caligo.waits.WaitStart
import com.yenreh.caligo.waits.WaitWords
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
 * process start.
 *
 * Apart from the wait service, with ids and channels of its own: the
 * rider's own waits keep their notifications and foreground state there,
 * and a trip that ends leaves them running, as they leave it. The waits a
 * planner trip starts for its rides run here instead, with a wait engine
 * of their own and ids apart: already in the foreground, the trip posts
 * them from the background too, as after a change with the screen off,
 * where a second foreground service would not be let start. They end with
 * the trip.
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
    private lateinit var waitWords: WaitWords
    private var wakeLock: PowerManager.WakeLock? = null

    /** Each trip wait's notification id, in the order they started */
    private val waitSlots = LinkedHashMap<WaitKey, Int>()

    /** Trip waits whose alert has gone out */
    private val waitsAlerted = HashSet<WaitKey>()

    private val endWaitFromDesk: (WaitKey, WaitEnding) -> Unit = { key, ending -> endWait(key, ending) }

    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        val container = (application as CaliGoApp).container
        desk = container.trips
        waits = container.waits
        scope = CoroutineScope(SupervisorJob() + desk.coroutineContext)
        engine = TripEngine(desk.source, desk.location, desk.now, scope, this)
        waitEngine = WaitEngine(waits.source, desk.now, scope, tripWaits)
        words = TripWords(resources)
        waitWords = WaitWords(resources)
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
            engine.start(start)
            val journey = checkNotNull(engine.journey)
            // Not allowed to follow the location: nothing runs, and the screen hears why
            if (!foreground(TripNotifications.TRIP_ID, notification(journey, desk.now(), TripEngine.BEAT.inWholeMilliseconds))) {
                engine.end(TripEnding.NO_LOCATION)
            }
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
        for (id in TripNotifications.waitIds) manager.cancel(id)
        waitSlots.clear()
        waits.detachTripWaits(endWaitFromDesk)
        releaseWakeLock()
        desk.detach(this)
        super.onDestroy()
    }

    internal fun start(start: TripStart) = engine.start(start)

    internal fun took(took: TookRide): Boolean = engine.took(took)

    internal fun alightAt(alight: Int?) = engine.alightAt(alight)

    internal fun end(ending: TripEnding) = engine.end(ending)

    /** A trip wait ended by a button, its swipe or its bell: by the rider, so not asked for again */
    internal fun endWait(key: WaitKey, ending: WaitEnding) {
        if (ending != WaitEnding.TOOK) engine.waitDeclined()
        waitEngine.end(key, ending)
    }

    /** The trip's wait engine, for tests to see that nothing runs */
    internal val tripWaitEngine: WaitEngine get() = waitEngine

    /** The engine, for tests to see that nothing runs */
    internal val tripEngine: TripEngine get() = engine

    // The warning channel off only silences the warning: the trip goes on, on its own channel
    override fun allowed(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
        TripNotifications.channelOn(this, TripChannel.TRIPS)

    private fun notification(journey: Journey, now: Long, nextPostIn: Long): Notification {
        val alerts = desk.alerts()
        val warningOn = TripNotifications.channelOn(this, TripChannel.warning(alerts))
        val next = waitEngine.waits.filter { it.key in journey.awaited }.minOfOrNull { it.last.arrivalTime }
        val notice = TripNotice.of(journey, now, alerts, words, nextPostIn, warningOn, nextBusAt = next)
        return TripNotifications.build(this, journey.startedAt, notice, alerts, words)
    }

    // allowed() holds before every post: the app's notifications are on
    @SuppressLint("MissingPermission")
    override fun show(journey: Journey, now: Long, nextPostIn: Long) {
        NotificationManagerCompat.from(this).notify(TripNotifications.TRIP_ID, notification(journey, now, nextPostIn))
        holdWakeLock(nextPostIn + WAKE_MARGIN_MS)
    }

    override fun ended(journey: Journey, ending: TripEnding) {
        desk.endedAs(journey, ending)
        NotificationManagerCompat.from(this).cancel(TripNotifications.TRIP_ID)
    }

    override fun idle() {
        // Nothing of a trip outlives it: its waits, had any been left, go too
        if (waitEngine.waits.isNotEmpty()) waitEngine.endAll(WaitEnding.STOPPED)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val manager = NotificationManagerCompat.from(this)
        manager.cancel(TripNotifications.TRIP_ID)
        manager.cancel(TripNotifications.PLACEHOLDER_ID)
        desk.location.stop()
        releaseWakeLock()
        desk.detach(this)
        // A start that came meanwhile keeps it running
        stopSelfResult(lastStartId)
    }

    override fun changed(journey: Journey?) {
        desk.changed(journey)
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

    /** What the trip's wait engine asks of the one that shows its waits: here, with ids apart */
    private val tripWaits = object : WaitHost {
        override fun allowed(): Boolean = NotificationManagerCompat.from(this@TripService).areNotificationsEnabled() &&
            WaitNotifications.channelOn(this@TripService, WaitChannel.WAITS)

        // allowed() holds before every post
        @SuppressLint("MissingPermission")
        override fun show(waits: List<Wait>, now: Long, nextPostIn: Long) {
            val context = this@TripService
            val alerts = desk.alerts()
            val alertOn = WaitNotifications.channelOn(context, WaitChannel.alert(alerts))
            val manager = NotificationManagerCompat.from(context)
            for (wait in waits) {
                val id = waitSlots.getOrPut(wait.key) { TripNotifications.waitIds.first { it !in waitSlots.values } }
                val notice = WaitNotice.of(wait, now, alerts, waitWords, nextPostIn, alertOn).let {
                    if (it.alerting && !waitsAlerted.add(wait.key)) it.copy(alerting = false) else it
                }
                manager.notify(id, WaitNotifications.build(context, id, wait, notice, alerts, waitWords, tripWait = true))
            }
            holdWakeLock(nextPostIn + WAKE_MARGIN_MS)
        }

        override fun ended(wait: Wait, ending: WaitEnding) {
            waitsAlerted.remove(wait.key)
            val id = waitSlots.remove(wait.key) ?: return
            NotificationManagerCompat.from(this@TripService).cancel(id)
        }

        override fun idle() {
            val manager = NotificationManagerCompat.from(this@TripService)
            for (id in TripNotifications.waitIds) manager.cancel(id)
            waitSlots.clear()
            waitsAlerted.clear()
        }

        override fun changed(waits: List<Wait>) {
            this@TripService.waits.tripWaitsChanged(waits.map { RunningWait(it.key, it.last.arrivalTime, it.listedAt) })
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

    private fun holdWakeLock(timeout: Long) {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(timeout)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    internal val holdsWakeLock: Boolean get() = wakeLock?.isHeld == true

    companion object {
        /** How long past the next post the wake lock holds, should that post be late */
        const val WAKE_MARGIN_MS = 30_000L

        private const val WAKE_LOCK_TAG = "CaliGo:trip"
    }
}
