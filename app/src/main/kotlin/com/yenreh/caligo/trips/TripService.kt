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
import com.yenreh.caligo.waits.WaitDesk
import com.yenreh.caligo.waits.WaitStart
import com.yenreh.caligo.domain.TripEnding
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
 * waits keep their own notifications and foreground state, and a trip
 * that ends leaves them running, as they leave it.
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
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        val container = (application as CaliGoApp).container
        desk = container.trips
        waits = container.waits
        scope = CoroutineScope(SupervisorJob() + desk.coroutineContext)
        engine = TripEngine(desk.source, desk.location, desk.now, scope, this)
        words = TripWords(resources)
        TripNotifications.createChannels(this)
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
        // Killed with a trip on: the waits it started go with it
        engine.journey?.awaited?.let { if (it.isNotEmpty()) stopWaits(it) }
        scope.cancel()
        desk.location.stop()
        NotificationManagerCompat.from(this).cancel(TripNotifications.TRIP_ID)
        releaseWakeLock()
        desk.detach(this)
        super.onDestroy()
    }

    internal fun start(start: TripStart) = engine.start(start)

    internal fun took(took: TookRide): Boolean = engine.took(took)

    internal fun alightAt(alight: Int?) = engine.alightAt(alight)

    internal fun end(ending: TripEnding) = engine.end(ending)

    /** The engine, for tests to see that nothing runs */
    internal val tripEngine: TripEngine get() = engine

    // The warning channel off only silences the warning: the trip goes on, on its own channel
    override fun allowed(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
        TripNotifications.channelOn(this, TripChannel.TRIPS)

    private fun notification(journey: Journey, now: Long, nextPostIn: Long): Notification {
        val alerts = desk.alerts()
        val warningOn = TripNotifications.channelOn(this, TripChannel.warning(alerts))
        val next = waits.running.value.filter { it.key in journey.awaited }.minOfOrNull { it.arrivalTime }
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

    // Ordinary waits, each with its notification and "I took it", in the waits' own service
    override fun startWaits(buses: List<AwaitedBus>): List<WaitKey> =
        buses.filter { waits.start(it, null) == WaitStart.STARTED }.map { it.key }

    override fun stopWaits(keys: List<WaitKey>) {
        for (key in keys) waits.stop(key)
    }

    override fun runningWaits(): Set<WaitKey> = waits.running.value.mapTo(HashSet()) { it.key }

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
