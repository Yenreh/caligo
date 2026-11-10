package com.yenreh.caligo.trips

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.domain.TripEnding

/**
 * The trip notification's Finish and its swipe, handed to the service
 * through the desk. With no service running, as in a process started
 * anew, there is no trip to end, and the process start has cancelled what
 * was left.
 */
class TripActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!intent.hasExtra(TripNotifications.EXTRA_STARTED_AT)) return
        val ending = when (intent.action) {
            TripNotifications.ACTION_FINISH -> TripEnding.FINISHED
            TripNotifications.ACTION_DISMISSED -> TripEnding.DISMISSED
            else -> return
        }
        val startedAt = intent.getLongExtra(TripNotifications.EXTRA_STARTED_AT, 0)
        (context.applicationContext as CaliGoApp).container.trips.act(startedAt, ending)
    }
}
