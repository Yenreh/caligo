@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yenreh.caligo.data.location

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.data.assertThrowsSuspend
import com.yenreh.caligo.domain.GeoPoint
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * The framework location as geolocator read it: whether it is on, the
 * permission and its prompt, the best last fix, the first fresh one
 */
@RunWith(AndroidJUnit4::class)
class AndroidLocationSourceTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val manager = application.getSystemService(LocationManager::class.java)
    private val shadow = shadowOf(manager)
    private val source = AndroidLocationSource(application)

    @Before
    fun providers() {
        shadow.setLocationEnabled(true)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
    }

    private fun grant(vararg permissions: String) = shadowOf(application).grantPermissions(*permissions)

    /** The moment the fixes' ages count from, taken once, so their gaps are exact */
    private val takenAt = System.currentTimeMillis()

    private fun fix(provider: String, latitude: Double, longitude: Double, ageMillis: Long = 0, accuracy: Float = 10f) =
        Location(provider).apply {
            this.latitude = latitude
            this.longitude = longitude
            this.accuracy = accuracy
            time = takenAt - ageMillis
        }

    // On or off

    @Test
    fun locationIsOnWithTheGpsOrTheNetwork() {
        assertTrue(source.isServiceEnabled())

        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        assertTrue(source.isServiceEnabled())

        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        assertFalse(source.isServiceEnabled())
    }

    // The permission

    @Test
    fun eitherPermissionIsEnough() {
        assertEquals(LocationPermission.DENIED, source.checkPermission())

        grant(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals(LocationPermission.GRANTED, source.checkPermission())
    }

    @Test
    fun theAnswerReadsAsGeolocatorReadsIt() {
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        fun read(answer: Map<String, Boolean>, rationale: Boolean = false) =
            AndroidLocationSource.permissionFromAnswer(answer, { LocationPermission.DENIED }) { rationale }

        // Approximate only is still a yes
        assertEquals(LocationPermission.GRANTED, read(mapOf(coarse to true, fine to false)))
        // Refused, and the system would explain itself: it may ask again
        assertEquals(LocationPermission.DENIED, read(mapOf(coarse to false, fine to false), rationale = true))
        // Refused with no explanation left: it no longer asks
        assertEquals(LocationPermission.DENIED_FOREVER, read(mapOf(coarse to false, fine to false)))
        // A prompt cancelled says nothing new
        assertEquals(LocationPermission.DENIED, read(emptyMap()))
    }

    @Test
    fun asksFromTheActivityAndHearsTheAnswer() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        source.attach(controller.get())
        controller.start().resume()
        shadowOf(application.packageManager)
            .setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION, true)

        val answer = async { source.requestPermission() }
        runCurrent()

        val asked = shadowOf(controller.get()).lastRequestedPermission
        assertEquals(AndroidLocationSource.PERMISSIONS.toList(), asked.requestedPermissions.toList())
        // A second request while the prompt shows waits for the same answer
        val again = async { source.requestPermission() }
        runCurrent()

        // As the activity hands the system's answer to its results; before
        // the looper runs, which has Robolectric answer yes on its own
        controller.get().activityResultRegistry.dispatchResult(
            asked.requestCode,
            Activity.RESULT_OK,
            Intent()
                .putExtra(RequestMultiplePermissions.EXTRA_PERMISSIONS, asked.requestedPermissions)
                .putExtra(
                    RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                    intArrayOf(PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_DENIED),
                ),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(LocationPermission.DENIED, answer.await())
        assertEquals(LocationPermission.DENIED, again.await())
    }

    @Test
    fun withNoActivityThereIsNoOneToAsk() = runTest {
        assertThrowsSuspend<IllegalStateException> { source.requestPermission() }
    }

    // The last fix

    @Test
    fun theLastFixNeedsThePermission() = runTest {
        shadow.simulateLocation(fix(LocationManager.GPS_PROVIDER, 3.45, -76.53))

        assertThrowsSuspend<LocationPermissionException> { source.lastKnownPosition() }
    }

    @Test
    fun theLastFixIsTheBestOfTheProviders() = runTest {
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        // A sharp GPS fix ten minutes old against a rough network fix of now
        shadow.simulateLocation(fix(LocationManager.GPS_PROVIDER, 3.40, -76.50, ageMillis = 600_000, accuracy = 5f))
        shadow.simulateLocation(fix(LocationManager.NETWORK_PROVIDER, 3.45, -76.53, accuracy = 60f))

        assertEquals(GeoPoint(3.45, -76.53), source.lastKnownPosition())
    }

    @Test
    fun theLastFixSaysHowOldAndHowExactItIs() = runTest {
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadow.simulateLocation(fix(LocationManager.NETWORK_PROVIDER, 3.45, -76.53, ageMillis = 90_000, accuracy = 40f))

        val known = checkNotNull(AndroidLocationSource(application, now = { takenAt }).lastKnownFix())
        assertEquals(KnownFix(GeoPoint(3.45, -76.53), 40.0, 90_000), known)
        assertTrue(known.fresh)
        assertFalse(known.copy(ageMillis = 3 * 60_000).fresh)
        assertFalse(known.copy(accuracyMeters = 500.0).fresh)
    }

    @Test
    fun aBalancedFixIsTheOneThePlatformHandsOver() = runTest {
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        val position = async { source.currentPosition(LocationAccuracy.BALANCED, 15.seconds) }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        shadow.simulateLocation(fix("fused", 3.4516, -76.532))
        shadow.simulateLocation(fix(LocationManager.NETWORK_PROVIDER, 3.4516, -76.532))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()

        assertEquals(GeoPoint(3.4516, -76.532), position.await())
    }

    @Test
    fun noFixKnownIsNull() = runTest {
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertNull(source.lastKnownPosition())
    }

    @Test
    fun aMoreAccurateFixOfAboutTheSameTimeWins() {
        val now = fix(LocationManager.NETWORK_PROVIDER, 3.45, -76.53, accuracy = 50f)
        val sharper = fix(LocationManager.GPS_PROVIDER, 3.46, -76.53, ageMillis = 30_000, accuracy = 8f)
        val muchOlder = fix(LocationManager.GPS_PROVIDER, 3.46, -76.53, ageMillis = 300_000, accuracy = 1f)

        assertTrue(AndroidLocationSource.isBetterLocation(sharper, now))
        assertFalse(AndroidLocationSource.isBetterLocation(muchOlder, now))
        assertTrue(AndroidLocationSource.isBetterLocation(now, null))
    }

    // A fresh fix

    /** The provider a request is listening to */
    private fun asked(): String? = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { shadow.getLocationRequests(it).isNotEmpty() }

    @Test
    fun aFreshFixIsTheFirstTheProviderGives() = runTest {
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        val position = async { source.currentPosition(LocationAccuracy.HIGH, 15.seconds) }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        val provider = checkNotNull(asked())

        shadow.simulateLocation(fix(provider, 3.4516, -76.532))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()

        assertEquals(GeoPoint(3.4516, -76.532), position.await())
        // Done listening
        assertNull(asked())
    }

    @Test
    fun noFixWithinTheTimeLimitStopsListening() = runTest {
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertThrowsSuspend<LocationTimeoutException> { source.currentPosition(LocationAccuracy.LOW, 8.seconds) }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(asked())
    }

    @Test
    fun aFreshFixNeedsLocationOnAndThePermission() = runTest {
        assertThrowsSuspend<LocationPermissionException> { source.currentPosition(LocationAccuracy.HIGH, 15.seconds) }

        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)

        assertThrowsSuspend<LocationServiceDisabledException> {
            source.currentPosition(LocationAccuracy.HIGH, 15.seconds)
        }
    }

    // The steps currentDevicePosition takes, with the framework below

    @Test
    fun locationOffIsUnavailableBeforeAnyPrompt() = runTest {
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)

        val error = assertThrowsSuspend<LocationUnavailableException> { source.currentDevicePosition() }
        assertFalse(error.permanentlyDenied)
    }

    @Test
    fun withoutAskingAMissingPermissionIsUnavailable() = runTest {
        val error = assertThrowsSuspend<LocationUnavailableException> {
            source.currentDevicePosition(requestPermission = false)
        }
        assertFalse(error.permanentlyDenied)
    }

    @Test
    fun theLastKnownPositionIsQuietlyNullWithoutThePermission() = runTest {
        assertNull(source.lastKnownDevicePosition())
    }
}
