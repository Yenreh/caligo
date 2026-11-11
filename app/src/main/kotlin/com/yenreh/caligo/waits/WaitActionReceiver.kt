package com.yenreh.caligo.waits

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.domain.WaitEnding

/**
 * "Stop waiting" and "No" of a wait's notification and its swipe, and its
 * "I took it" in a build without trips, handed to the
 * service through the desk. With no service running, as in a process
 * started anew, there is no wait to end, and the process start has
 * cancelled what was left.
 */
class WaitActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = WaitNotifications.keyOf(intent) ?: return
        val waits = (context.applicationContext as CaliGoApp).container.waits
        when (intent.action) {
            // Ends that wait and the others at its stop, as the rider left it
            WaitNotifications.ACTION_TOOK -> waits.act(key, WaitEnding.TOOK)
            WaitNotifications.ACTION_STOP -> waits.act(key, WaitEnding.STOPPED)
            WaitNotifications.ACTION_DISMISSED -> waits.act(key, WaitEnding.DISMISSED)
            WaitNotifications.ACTION_STOP_GROUP -> waits.actGroup(key, WaitEnding.STOPPED)
            WaitNotifications.ACTION_DISMISSED_GROUP -> waits.actGroup(key, WaitEnding.DISMISSED)
        }
    }
}
