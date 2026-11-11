package com.yenreh.caligo.trips

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.domain.TripEnding
import com.yenreh.caligo.trips.TripFixtures.NOW
import com.yenreh.caligo.trips.TripFixtures.plan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The desk where a trip starts, when the system will not start its service */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TripDeskTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    /** As Android 12 and later refuse a foreground service from the background */
    private val refusing = object : ContextWrapper(app) {
        override fun startForegroundService(service: Intent): ComponentName? = throw IllegalStateException("not allowed from the background")
    }

    private val desk = TripDesk(refusing, FakeTripSource { NOW }, FakeLocationUpdates(), CoroutineScope(StandardTestDispatcher()), now = { NOW })

    @Test
    fun aStartTheSystemRefusesEndsBlockedAndSaysSo() {
        desk.start(plan)
        assertNull(desk.running.value)
        val ended = checkNotNull(desk.ended.value)
        assertEquals(TripEnding.BLOCKED, ended.ending)
        assertEquals(true, ended.refused)
        assertEquals(plan, ended.journey.ride?.plan)
        // Nothing waits for a service that never comes: the next start tries afresh
        desk.start(plan)
        assertNull(desk.running.value)
        assertEquals(true, desk.ended.value?.refused)
    }
}
