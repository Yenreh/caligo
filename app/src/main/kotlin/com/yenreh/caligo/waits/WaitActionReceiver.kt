package com.yenreh.caligo.waits

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.domain.WaitEnding

/**
 * "Stop waiting" and "No" of a wait's notification and its swipe, handed to the
 * service through the desk. With no service running, as in a process
 * started anew, there is no wait to end, and the process start has
 * cancelled what was left.
 */
class WaitActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = WaitNotifications.keyOf(intent) ?: return
        val ending = when (intent.action) {
            WaitNotifications.ACTION_STOP -> WaitEnding.STOPPED
            WaitNotifications.ACTION_DISMISSED -> WaitEnding.DISMISSED
            else -> return
        }
        val container = (context.applicationContext as CaliGoApp).container
        // A wait a trip started is the trip service's own
        if (intent.getBooleanExtra(WaitNotifications.EXTRA_TRIP_WAIT, false)) {
            container.trips.endWait(key, ending)
        } else {
            container.waits.act(key, ending)
        }
    }
}
