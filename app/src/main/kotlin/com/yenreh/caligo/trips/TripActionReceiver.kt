package com.yenreh.caligo.trips

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.domain.TripEnding

/**
 * The trip notification's Finish, its "A47: I took it", its Undo and its swipe, handed to the service
 * through the desk. With no service running, as in a process started
 * anew, there is no trip to end, and the process start has cancelled what
 * was left.
 */
class TripActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!intent.hasExtra(TripNotifications.EXTRA_STARTED_AT)) return
        val startedAt = intent.getLongExtra(TripNotifications.EXTRA_STARTED_AT, 0)
        val trips = (context.applicationContext as CaliGoApp).container.trips
        if (intent.action == TripNotifications.ACTION_TAKE_LINE) {
            intent.getStringExtra(TripNotifications.EXTRA_LINE)?.let { trips.takeLine(startedAt, it) }
            return
        }
        if (intent.action == TripNotifications.ACTION_UNDO) return trips.undo(startedAt)
        val ending = when (intent.action) {
            TripNotifications.ACTION_FINISH -> TripEnding.FINISHED
            TripNotifications.ACTION_DISMISSED -> TripEnding.DISMISSED
            else -> return
        }
        trips.act(startedAt, ending)
    }
}
