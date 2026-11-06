package com.yenreh.caligo

import android.app.Application

class CaliGoApp : Application() {
    /** Made on first use, so a process that never needs the data skips it */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        seedOnLaunch(this)
    }
}
