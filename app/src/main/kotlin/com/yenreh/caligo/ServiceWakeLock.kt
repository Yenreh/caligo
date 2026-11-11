package com.yenreh.caligo

import android.content.Context
import android.os.PowerManager

/**
 * A service's partial wake lock, held a while at a time: each hold
 * replaces the last one's timeout, so it lets go by itself if anything
 * stalls. Made on the first hold, once the service has its context.
 */
class ServiceWakeLock(private val context: Context, private val tag: String) {
    private var lock: PowerManager.WakeLock? = null

    /** Held for [timeout] milliseconds from now */
    fun hold(timeout: Long) {
        val held = lock ?: context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
            // Not reference counted: each hold replaces the last one's timeout
            .apply { setReferenceCounted(false) }
            .also { lock = it }
        held.acquire(timeout)
    }

    fun release() {
        lock?.let { if (it.isHeld) it.release() }
    }

    val isHeld: Boolean get() = lock?.isHeld == true
}
