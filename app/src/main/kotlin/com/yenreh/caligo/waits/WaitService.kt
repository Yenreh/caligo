package com.yenreh.caligo.waits

import android.annotation.SuppressLint
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
import com.yenreh.caligo.domain.FadingArrivals
import com.yenreh.caligo.domain.RunningWait
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.domain.WaitEnding
import com.yenreh.caligo.domain.WaitKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The one owner of every wait the rider starts from a bell, and of each
 * one's notification (a trip's waits run in the trip service): it posts
 * them, moves its own foreground notification to another wait when the
 * wait carrying it ends, and cancels them.
 *
 * It never runs without a notification of its own: it starts as the
 * first wait is shown and stops as the last one ends. Not sticky: killed,
 * it does not come back, and what it showed goes with the timeout each
 * notification carries, or at the next process start.
 *
 * A partial wake lock keeps the rounds on time with the screen off: the
 * alert near the stop is the point of waiting. Each post holds it until a
 * little after the next one, so it lets go by itself if anything stalls,
 * and it is released as the last wait ends.
 */
class WaitService : Service(), WaitHost {
    private lateinit var desk: WaitDesk
    private lateinit var scope: CoroutineScope
    private lateinit var engine: WaitEngine
    private lateinit var words: WaitWords
    private var wakeLock: PowerManager.WakeLock? = null

    /** Each wait's notification id, in the order they started */
    private val slots = LinkedHashMap<WaitKey, Int>()

    /** Waits whose alert has gone out: posts the engine has not counted yet stay silent */
    private val alerted = HashSet<WaitKey>()

    /** The wait whose notification is the service's own */
    private var carrier: WaitKey? = null

    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        desk = (application as CaliGoApp).container.waits
        scope = CoroutineScope(SupervisorJob() + desk.coroutineContext)
        engine = WaitEngine(desk.source, desk.now, scope, this)
        words = WaitWords(resources)
        WaitNotifications.createChannels(this)
        desk.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        desk.attach(this)
        for ((bus, seed) in desk.takePending()) engine.start(bus, seed)
        // Started as a foreground service: it must say so at once, even
        // with nothing left to wait for
        if (engine.waits.isEmpty()) {
            foreground(WaitNotifications.PLACEHOLDER_ID, WaitNotifications.placeholder(this))
            idle()
        } else {
            carrier = null
            show(engine.waits, desk.now(), WaitEngine.BEAT.inWholeMilliseconds)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        val manager = NotificationManagerCompat.from(this)
        for (id in slots.values) manager.cancel(id)
        slots.clear()
        releaseWakeLock()
        desk.detach(this)
        super.onDestroy()
    }

    /** Wait for [bus] as well */
    internal fun add(bus: AwaitedBus, seed: FadingArrivals?): WaitStart = engine.start(bus, seed)

    internal fun end(key: WaitKey, ending: WaitEnding) = engine.end(key, ending)

    /** The engine, for tests to see that nothing runs */
    internal val waitEngine: WaitEngine get() = engine

    // The alert channel off only silences the alert: the waits go on, on their own channel
    override fun allowed(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
        WaitNotifications.channelOn(this, WaitChannel.WAITS)

    // allowed() holds before every post: the app's notifications are on
    @SuppressLint("MissingPermission")
    override fun show(waits: List<Wait>, now: Long, nextPostIn: Long) {
        val alerts = desk.alerts()
        val alertOn = WaitNotifications.channelOn(this, WaitChannel.alert(alerts))
        val manager = NotificationManagerCompat.from(this)
        for (wait in waits) {
            val id = slots.getOrPut(wait.key) { WaitNotifications.ids.first { it !in slots.values } }
            val notice = WaitNotice.of(wait, now, alerts, words, nextPostIn, alertOn).let {
                // One alert per wait, whoever posts first
                if (it.alerting && !alerted.add(wait.key)) it.copy(alerting = false) else it
            }
            val notification = WaitNotifications.build(this, id, wait, notice, alerts, words)
            if (carrier == null) {
                foreground(id, notification)
                carrier = wait.key
            } else {
                manager.notify(id, notification)
            }
        }
        holdWakeLock(nextPostIn + WAKE_MARGIN_MS)
    }

    override fun ended(wait: Wait, ending: WaitEnding) {
        alerted.remove(wait.key)
        val id = slots.remove(wait.key) ?: return
        if (carrier == wait.key) {
            carrier = null
            // Moved before the old one goes, so the service always shows one
            engine.waits.firstOrNull()?.let { next -> show(listOf(next), desk.now(), WaitEngine.BEAT.inWholeMilliseconds) }
        }
        NotificationManagerCompat.from(this).cancel(id)
    }

    override fun idle() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val manager = NotificationManagerCompat.from(this)
        for (id in slots.values) manager.cancel(id)
        manager.cancel(WaitNotifications.PLACEHOLDER_ID)
        slots.clear()
        alerted.clear()
        carrier = null
        releaseWakeLock()
        desk.detach(this)
        // A start that came meanwhile keeps it running
        stopSelfResult(lastStartId)
    }

    override fun changed(waits: List<Wait>) {
        desk.changed(waits.map { RunningWait(it.key, it.last.arrivalTime, it.listedAt) })
    }

    private fun foreground(id: Int, notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, id, notification, type)
    }

    private fun holdWakeLock(timeout: Long) {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        // Not reference counted: each hold replaces the last one's timeout
        lock.acquire(timeout)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    internal val holdsWakeLock: Boolean get() = wakeLock?.isHeld == true

    companion object {
        /** How long past the next post the wake lock holds, should that post be late */
        const val WAKE_MARGIN_MS = 30_000L

        private const val WAKE_LOCK_TAG = "CaliGo:wait"
    }
}
