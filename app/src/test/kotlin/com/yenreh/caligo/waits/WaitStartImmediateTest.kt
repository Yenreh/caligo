package com.yenreh.caligo.waits

import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.CaliGoApp
import com.yenreh.caligo.waits.WaitFixtures.NOW
import com.yenreh.caligo.waits.WaitFixtures.answer
import com.yenreh.caligo.waits.WaitFixtures.awaited
import com.yenreh.caligo.waits.WaitFixtures.t31
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * The wait service on a dispatcher that runs a coroutine at once, as the
 * main thread's immediate one does: a wait's first round runs inside its
 * start. The service is in the foreground before that, and ends clean.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WaitStartImmediateTest {
    private val scheduler = TestCoroutineScheduler()
    private val now = { NOW + scheduler.currentTime }
    private val app = ApplicationProvider.getApplicationContext<CaliGoApp>()
    private val desk = app.container.waits
    private val manager = app.getSystemService(NotificationManager::class.java)
    private var controller: ServiceController<WaitService>? = null

    @Before
    fun setUp() {
        desk.source = FakeWaitSource({ _, at -> answer(at, t31) }, now)
        desk.now = now
        desk.coroutineContext = UnconfinedTestDispatcher(scheduler)
    }

    @After
    fun tearDown() {
        controller?.destroy()
    }

    private fun start() {
        assertEquals(WaitStart.STARTED, desk.start(awaited(t31), null))
        controller = Robolectric.buildService(WaitService::class.java, Intent(app, WaitService::class.java)).create().startCommand(0, 1)
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aWaitStartsWithItsOwnNotificationAsTheServices() {
        start()
        val shadow = shadowOf(checkNotNull(controller).get())
        val ids = shadowOf(manager).activeNotifications.map { it.id }
        assertEquals(listOf(shadow.lastForegroundNotificationId), ids)
        assertTrue(WaitNotifications.PLACEHOLDER_ID !in ids)
        assertFalse(shadow.isForegroundStopped)
    }

    @Test
    fun aWaitWithTheWaitsChannelOffEndsAtItsStart() {
        WaitNotifications.createChannels(app)
        manager.getNotificationChannel(WaitChannel.WAITS.id).importance = NotificationManager.IMPORTANCE_NONE
        start()
        val service = checkNotNull(controller).get()
        val shadow = shadowOf(service)
        assertTrue("went to the foreground", shadow.lastForegroundNotificationId != 0)
        assertTrue("stopped", shadow.isStoppedBySelf)
        assertTrue("out of the foreground", shadow.isForegroundStopped)
        assertTrue(shadowOf(manager).activeNotifications.isEmpty())
        assertTrue(service.waitEngine.isIdle && service.waitEngine.waits.isEmpty())
        assertTrue(desk.running.value.isEmpty())
    }
}
