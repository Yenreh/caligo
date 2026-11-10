package com.yenreh.caligo

import android.app.Application
import com.yenreh.caligo.waits.WaitNotifications

class CaliGoApp : Application() {
    /** Made on first use, so a process that never needs the data skips it */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // No wait runs in a process just started: none of their notifications may stay
        WaitNotifications.cancelLeftovers(this)
        seedOnLaunch(this)
    }
}
