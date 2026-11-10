package com.yenreh.caligo.data.location

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.domain.TripFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The fixes a trip follows, from the framework's LocationManager: asked, delivered, let go */
@RunWith(AndroidJUnit4::class)
class AndroidLocationUpdatesTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val manager = application.getSystemService(LocationManager::class.java)
    private val shadow = shadowOf(manager)
    private val updates = AndroidLocationUpdates(application) { 1_000L }
    private val fixes = mutableListOf<TripFix>()
    private var lost = 0

    @Before
    fun providers() {
        shadow.setLocationEnabled(true)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun asked(): String? = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { shadow.getLocationRequests(it).isNotEmpty() }

    private fun request(quality: FixQuality) {
        updates.request(quality, { fixes.add(it) }, { lost++ })
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun usableWithLocationOnAndThePermission() {
        assertTrue(updates.usable())
        shadow.setLocationEnabled(false)
        assertFalse(updates.usable())
    }

    @Test
    fun notUsableOnceThePermissionIsTakenBack() {
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertFalse(updates.usable())
        request(FixQuality.BALANCED)
        assertNull(updates.requested)
        assertEquals(1, lost)
    }

    @Test
    fun fixesComeAtTheQualityAskedUntilStopped() {
        request(FixQuality.BALANCED)
        val provider = checkNotNull(asked())
        val request = shadow.getLocationRequests(provider).single()
        assertEquals(FixQuality.BALANCED.intervalMillis, request.intervalMillis)
        shadow.simulateLocation(
            Location(provider).apply {
                latitude = 3.45
                longitude = -76.53
                accuracy = 25f
            },
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(25.0, fixes.single().accuracy, 0.0)
        assertEquals(1_000L, fixes.single().at)

        // Near the stop: high accuracy, the one request replacing the other
        request(FixQuality.HIGH)
        assertEquals(FixQuality.HIGH, updates.requested)
        assertEquals(FixQuality.HIGH.intervalMillis, shadow.getLocationRequests(checkNotNull(asked())).single().intervalMillis)

        updates.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(updates.requested)
        assertNull(asked())
    }

    @Test
    fun theProviderTurnedOffSaysSo() {
        request(FixQuality.HIGH)
        val provider = checkNotNull(asked())
        shadow.setProviderEnabled(provider, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, lost)
    }
}
