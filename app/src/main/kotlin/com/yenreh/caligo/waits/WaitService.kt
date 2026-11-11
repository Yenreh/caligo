package com.yenreh.caligo.waits

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.yenreh.caligo.AppFeatures
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.ServiceWakeLock
import com.yenreh.caligo.channelOn
import com.yenreh.caligo.domain.AskPoint
import com.yenreh.caligo.domain.AwaitedBus
import com.yenreh.caligo.domain.FadingArrivals
import com.yenreh.caligo.domain.RunningWait
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.domain.WaitAnswer
import com.yenreh.caligo.domain.WaitEnding
import com.yenreh.caligo.domain.WaitKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The one owner of every wait the rider starts from a bell, and of their
 * notifications (a trip's waits run in the trip service): one for each
 * stop as the rider saw it, its waits together, a wait alone as it
 * always was. It posts them, leaves out a post the same as the last one
 * within [WaitNotice.REPOST_AFTER_MS], moves its own foreground
 * notification to another stop's when the group carrying it ends, and
 * cancels them.
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
    private val wakeLock = ServiceWakeLock(this, WAKE_LOCK_TAG)

    /** Each stop's notification id, in the order their first wait started */
    private val slots = LinkedHashMap<WaitGroup, Int>()

    /** Waits whose alert has gone out: posts the engine has not counted yet stay silent */
    private val alerted = HashSet<WaitKey>()

    /** The stop whose notification is the service's own */
    private var carrier: WaitGroup? = null

    /** What each stop's notification last showed, its waits and where their stops are asked about included, and when it was posted */
    private val posted = HashMap<WaitGroup, Pair<Any, Long>>()

    private var lastStartId = 0

    private lateinit var features: AppFeatures

    override fun onCreate() {
        super.onCreate()
        desk = (application as CaliGoApp).container.waits
        features = (application as CaliGoApp).container.features
        scope = CoroutineScope(SupervisorJob() + desk.coroutineContext)
        engine = WaitEngine(desk.source, desk.now, scope, this)
        words = WaitWords(resources)
        WaitNotifications.createChannels(this)
        desk.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        desk.attach(this)
        // Started as a foreground service: it must say so at once, even
        // with nothing left to wait for, and before anything runs: on the
        // main thread's immediate dispatcher a wait's first round runs
        // inside start, and may end every wait there
        if (carrier == null) foreground(WaitNotifications.PLACEHOLDER_ID, WaitNotifications.placeholder(this))
        for ((bus, seed) in desk.takePending()) engine.start(bus, seed)
        if (engine.waits.isEmpty()) {
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
        wakeLock.release()
        desk.detach(this)
        super.onDestroy()
    }

    /** Wait for [bus] as well */
    internal fun add(bus: AwaitedBus, seed: FadingArrivals?): WaitStart = engine.start(bus, seed)

    /** End [key]'s wait as [ending]; taken, the other waits at its stop end too: the rider is gone */
    internal fun end(key: WaitKey, ending: WaitEnding) {
        val group = engine.waits.firstOrNull { it.key == key }?.group ?: return
        if (ending != WaitEnding.TOOK) return engine.end(key, ending)
        closing(group) {
            engine.end(key, ending)
            for (other in engine.waits.filter { it.group == group }) engine.end(other.key, WaitEnding.STOPPED)
        }
    }

    /** The stop whose waits all end at once: its notification is not shown again on the way */
    private var closing: WaitGroup? = null

    private inline fun closing(group: WaitGroup, block: () -> Unit) {
        closing = group
        try {
            block()
        } finally {
            closing = null
        }
    }

    /** End every wait at the stop [key]'s wait is at as [ending]: "Stop waiting" or the swipe of its notification */
    internal fun endGroup(key: WaitKey, ending: WaitEnding) {
        val group = engine.waits.firstOrNull { it.key == key }?.group ?: return
        closing(group) {
            for (wait in engine.waits.filter { it.group == group }) engine.end(wait.key, ending)
        }
    }

    /** The engine, for tests to see that nothing runs */
    internal val waitEngine: WaitEngine get() = engine

    // The alert channel off only silences the alert: the waits go on, on their own channel
    override fun allowed(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
        channelOn(this, WaitChannel.WAITS)

    // allowed() holds before every post: the app's notifications are on
    @SuppressLint("MissingPermission")
    override fun show(waits: List<Wait>, now: Long, nextPostIn: Long) {
        val alerts = desk.alerts()
        val alertOn = channelOn(this, WaitChannel.alert(alerts))
        val manager = NotificationManagerCompat.from(this)
        for ((group, members) in waits.groupBy { it.group }) {
            val id = slots.getOrPut(group) { WaitNotifications.ids.first { it !in slots.values } }
            val notice = WaitNotice.ofGroup(members, now, alerts, words, nextPostIn, alertOn, alerted)
            // One alert per wait, whoever posts first
            if (notice.alerting) members.firstOrNull { it.alertDue(now) && it.key !in alerted }?.let { alerted.add(it.key) }
            // What its buttons and its tap carry too
            val shown = notice.copy(timeoutAfter = 0) to members.map { it.key to it.point }
            val last = posted[group]
            // The same as on show: not posted again for a while, the beat still kept awake
            if (carrier != null && last != null && last.first == shown && now - last.second < WaitNotice.REPOST_AFTER_MS) continue
            posted[group] = shown to now
            val notification = WaitNotifications.build(this, id, members, notice, alerts, words, features.trips)
            if (carrier == null) {
                foreground(id, notification)
                carrier = group
                // The placeholder that carried the service until now
                manager.cancel(WaitNotifications.PLACEHOLDER_ID)
            } else {
                posts++
                manager.notify(id, notification)
            }
        }
        wakeLock.hold(nextPostIn + WAKE_MARGIN_MS)
    }

    /** How many times a wait's notification was posted, past the first, for tests to count */
    internal var posts = 0
        private set

    override fun ended(wait: Wait, ending: WaitEnding) {
        alerted.remove(wait.key)
        val group = wait.group
        // Its stop's notification lives on while any of its waits does, shown again as they are
        val left = engine.waits.filter { it.group == group }
        if (left.isNotEmpty()) {
            posted.remove(group)
            if (group != closing) show(left, desk.now(), WaitEngine.BEAT.inWholeMilliseconds)
            return
        }
        posted.remove(group)
        val id = slots.remove(group) ?: return
        if (carrier == group) {
            carrier = null
            // Moved before the old one goes, so the service always shows one
            engine.waits.firstOrNull()?.group?.let { next ->
                show(engine.waits.filter { it.group == next }, desk.now(), WaitEngine.BEAT.inWholeMilliseconds)
            }
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
        posted.clear()
        carrier = null
        wakeLock.release()
        desk.detach(this)
        // A start that came meanwhile keeps it running
        stopSelfResult(lastStartId)
    }

    override fun changed(waits: List<Wait>) {
        desk.changed(waits.map { RunningWait(it.key, it.last.arrivalTime, it.listedAt) })
    }

    // Each wait reads its own stop from it already
    override fun answered(point: AskPoint, answer: WaitAnswer) {}

    private fun foreground(id: Int, notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, id, notification, type)
    }

    internal val holdsWakeLock: Boolean get() = wakeLock.isHeld

    companion object {
        /** How long past the next post the wake lock holds, should that post be late */
        const val WAKE_MARGIN_MS = 30_000L

        private const val WAKE_LOCK_TAG = "CaliGo:wait"
    }
}
