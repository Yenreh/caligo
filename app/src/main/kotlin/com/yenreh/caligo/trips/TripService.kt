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
import com.yenreh.caligo.domain.Trip
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.domain.TripPlan
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
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        desk = (application as CaliGoApp).container.trips
        scope = CoroutineScope(SupervisorJob() + desk.coroutineContext)
        engine = TripEngine(desk.source, desk.location, desk.now, scope, this)
        words = TripWords(resources)
        TripNotifications.createChannels(this)
        desk.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        desk.attach(this)
        val plan = desk.takePending()
        // Started as a foreground service: it must say so at once
        if (plan == null && engine.trip == null) {
            if (foreground(TripNotifications.PLACEHOLDER_ID, TripNotifications.placeholder(this))) idle() else stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (plan != null) {
            engine.start(plan)
            val trip = checkNotNull(engine.trip)
            // Not allowed to follow the location: nothing runs, and the screen hears why
            if (!foreground(TripNotifications.TRIP_ID, notification(trip, desk.now(), TripEngine.BEAT.inWholeMilliseconds))) {
                engine.end(TripEnding.NO_LOCATION)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        desk.location.stop()
        NotificationManagerCompat.from(this).cancel(TripNotifications.TRIP_ID)
        releaseWakeLock()
        desk.detach(this)
        super.onDestroy()
    }

    internal fun start(plan: TripPlan) = engine.start(plan)

    internal fun alightAt(alight: Int?) = engine.alightAt(alight)

    internal fun end(ending: TripEnding) = engine.end(ending)

    /** The engine, for tests to see that nothing runs */
    internal val tripEngine: TripEngine get() = engine

    // The warning channel off only silences the warning: the trip goes on, on its own channel
    override fun allowed(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
        TripNotifications.channelOn(this, TripChannel.TRIPS)

    private fun notification(trip: Trip, now: Long, nextPostIn: Long): Notification {
        val alerts = desk.alerts()
        val warningOn = TripNotifications.channelOn(this, TripChannel.warning(alerts))
        val notice = TripNotice.of(trip, now, alerts, words, nextPostIn, warningOn)
        return TripNotifications.build(this, trip.startedAt, notice, alerts, words)
    }

    // allowed() holds before every post: the app's notifications are on
    @SuppressLint("MissingPermission")
    override fun show(trip: Trip, now: Long, nextPostIn: Long) {
        NotificationManagerCompat.from(this).notify(TripNotifications.TRIP_ID, notification(trip, now, nextPostIn))
        holdWakeLock(nextPostIn + WAKE_MARGIN_MS)
    }

    override fun ended(trip: Trip, ending: TripEnding) {
        desk.endedAs(trip, ending)
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

    override fun changed(trip: Trip?) {
        desk.changed(trip)
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
