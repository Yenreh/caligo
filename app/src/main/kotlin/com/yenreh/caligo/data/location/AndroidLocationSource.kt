package com.yenreh.caligo.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.yenreh.caligo.domain.GeoPoint
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [LocationSource] on the framework's LocationManager, as geolocator's
 * LocationManagerClient works without Play services: the same providers,
 * the same choice of the best last fix, the first fix of a request as the
 * current one.
 *
 * Asking for the permission takes an activity: [attach] lends it the one
 * on screen. The question outlives the activity, so a rotation while the
 * prompt shows still delivers the answer.
 */
class AndroidLocationSource(
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) : LocationSource {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)

    /** The activity to ask from, and its way to ask; main thread only */
    private class Asker(val activity: ComponentActivity, val launcher: ActivityResultLauncher<Array<String>>)

    private var asker: Asker? = null

    /** The question on screen, which every request asks along with */
    private var pending: CompletableDeferred<LocationPermission>? = null

    override fun isServiceEnabled(): Boolean {
        val manager = manager ?: return false
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    override fun checkPermission(): LocationPermission =
        if (PERMISSIONS.any(::granted)) LocationPermission.GRANTED else LocationPermission.DENIED

    override fun approximateOnly(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Ask from [activity] from now on, until it is destroyed. Call in its
     * onCreate, before it starts, as every activity result must be.
     */
    fun attach(activity: ComponentActivity) {
        val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            answered(permissionFromAnswer(it, ::checkPermission) { permission ->
                ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            })
        }
        val mine = Asker(activity, launcher)
        asker = mine
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (asker === mine) asker = null
                // Closed for good: no answer is coming
                if (activity.isFinishing) answered(checkPermission())
            }
        })
    }

    private fun answered(permission: LocationPermission) {
        pending?.complete(permission)
        pending = null
    }

    override suspend fun requestPermission(): LocationPermission = withContext(Dispatchers.Main.immediate) {
        val waiting = pending ?: run {
            val asking = asker ?: throw IllegalStateException("No activity to ask the location permission from")
            CompletableDeferred<LocationPermission>().also {
                pending = it
                asking.launcher.launch(PERMISSIONS)
            }
        }
        waiting.await()
    }

    // The permission is checked first, as geolocator does before reading
    @SuppressLint("MissingPermission")
    override suspend fun lastKnownFix(): KnownFix? {
        if (checkPermission() != LocationPermission.GRANTED) throw LocationPermissionException()
        val manager = manager ?: return null

        var best: Location? = null
        for (provider in manager.getProviders(true)) {
            // GPS needs the fine permission, which coarse alone lacks
            val location = try {
                manager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } ?: continue
            if (isBetterLocation(location, best)) best = location
        }
        return best?.let {
            KnownFix(
                GeoPoint(it.latitude, it.longitude),
                accuracyMeters = if (it.hasAccuracy()) it.accuracy.toDouble() else null,
                ageMillis = (now() - it.time).coerceAtLeast(0),
            )
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun currentPosition(accuracy: LocationAccuracy, timeLimit: Duration): GeoPoint {
        if (checkPermission() != LocationPermission.GRANTED) throw LocationPermissionException()
        val manager = manager ?: throw LocationServiceDisabledException()
        if (!isServiceEnabled()) throw LocationServiceDisabledException()
        val provider = provider(manager) ?: throw LocationServiceDisabledException()
        if (accuracy == LocationAccuracy.BALANCED) return balanced(manager, provider, timeLimit)

        val request = LocationRequestCompat.Builder(0)
            .setMinUpdateIntervalMillis(0)
            .setMinUpdateDistanceMeters(0f)
            .setQuality(
                when (accuracy) {
                    LocationAccuracy.LOW -> LocationRequestCompat.QUALITY_LOW_POWER
                    LocationAccuracy.BALANCED -> LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
                    LocationAccuracy.HIGH -> LocationRequestCompat.QUALITY_HIGH_ACCURACY
                },
            )
            .build()

        val fix = withTimeoutOrNull(timeLimit) {
            suspendCancellableCoroutine<Location> { continuation ->
                val listener = object : LocationListenerCompat {
                    override fun onLocationChanged(location: Location) {
                        LocationManagerCompat.removeUpdates(manager, this)
                        if (continuation.isActive) continuation.resume(location)
                    }

                    override fun onProviderDisabled(disabled: String) {
                        if (disabled != provider) return
                        LocationManagerCompat.removeUpdates(manager, this)
                        if (continuation.isActive) continuation.resumeWithException(LocationServiceDisabledException())
                    }
                }
                continuation.invokeOnCancellation { LocationManagerCompat.removeUpdates(manager, listener) }
                LocationManagerCompat.requestLocationUpdates(
                    manager,
                    provider,
                    request,
                    ContextCompat.getMainExecutor(context),
                    listener,
                )
            }
        } ?: throw LocationTimeoutException(timeLimit)
        return GeoPoint(fix.latitude, fix.longitude)
    }

    /**
     * One fix at balanced accuracy, as the platform hands it over: on
     * Android 12 and later the fused provider asked for balanced power
     * through LocationManager.getCurrentLocation with a LocationRequest;
     * before, the network provider, else [provider], through
     * LocationManagerCompat.getCurrentLocation. Both may answer at once with
     * a fix a few seconds old: the platform's from Android 11 "may return
     * locations from the very recent past (on the order of several
     * seconds), but will never return older locations", and the compat one
     * before it takes a last known fix under 10 s old.
     */
    @SuppressLint("MissingPermission")
    private suspend fun balanced(manager: LocationManager, provider: String, timeLimit: Duration): GeoPoint {
        val enabled = manager.getProviders(true)
        val fix = withTimeoutOrNull(timeLimit) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val cancel = CancellationSignal()
                continuation.invokeOnCancellation { cancel.cancel() }
                val executor = ContextCompat.getMainExecutor(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in enabled) {
                    val request = LocationRequest.Builder(0)
                        .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                        .setDurationMillis(timeLimit.inWholeMilliseconds)
                        .build()
                    manager.getCurrentLocation(LocationManager.FUSED_PROVIDER, request, cancel, executor) {
                        if (continuation.isActive) continuation.resume(it)
                    }
                } else {
                    val network = LocationManager.NETWORK_PROVIDER.takeIf { it in enabled } ?: provider
                    LocationManagerCompat.getCurrentLocation(manager, network, cancel, executor) {
                        if (continuation.isActive) continuation.resume(it)
                    }
                }
            }
        } ?: throw LocationTimeoutException(timeLimit)
        return GeoPoint(fix.latitude, fix.longitude)
    }

    /**
     * The provider to ask, as geolocator chooses one: the platform's fused
     * provider where there is one, else GPS, else the network, else any
     * enabled. GPS only with the fine permission, which it requires.
     */
    private fun provider(manager: LocationManager): String? {
        val enabled = manager.getProviders(true)
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in enabled ->
                LocationManager.FUSED_PROVIDER
            fine && LocationManager.GPS_PROVIDER in enabled -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in enabled -> LocationManager.NETWORK_PROVIDER
            else -> enabled.firstOrNull { it != LocationManager.GPS_PROVIDER || fine }
        }
    }

    companion object {
        /** As the Flutter manifest declares them, and geolocator asks for them */
        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

        private const val TWO_MINUTES = 120_000L

        /**
         * What the system's answer means, as geolocator reads it: granted
         * when either permission is; denied for good when the system would
         * not explain itself again, which it does once it stops asking. An
         * empty answer, a prompt cancelled, leaves the permission as it was:
         * geolocator never answered one.
         */
        fun permissionFromAnswer(
            answer: Map<String, Boolean>,
            current: () -> LocationPermission,
            showsRationale: (String) -> Boolean,
        ): LocationPermission = when {
            answer.isEmpty() -> current()
            answer.values.any { it } -> LocationPermission.GRANTED
            PERMISSIONS.any(showsRationale) -> LocationPermission.DENIED
            else -> LocationPermission.DENIED_FOREVER
        }

        /** Geolocator's choice between two fixes, from Android's own sample */
        fun isBetterLocation(location: Location, best: Location?): Boolean {
            if (best == null) return true

            val timeDelta = location.time - best.time
            if (timeDelta > TWO_MINUTES) return true
            if (timeDelta < -TWO_MINUTES) return false
            val isNewer = timeDelta > 0

            // Truncated to whole metres, as geolocator's int cast
            val accuracyDelta = (location.accuracy - best.accuracy).toInt()
            val isLessAccurate = accuracyDelta > 0
            val isMoreAccurate = accuracyDelta < 0
            val isSignificantlyLessAccurate = accuracyDelta > 200
            val isFromSameProvider = location.provider != null && location.provider == best.provider

            return isMoreAccurate ||
                (isNewer && !isLessAccurate) ||
                (isNewer && !isSignificantlyLessAccurate && isFromSameProvider)
        }
    }
}
