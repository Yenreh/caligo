package com.yenreh.caligo.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.yenreh.caligo.domain.GeoPoint
import com.yenreh.caligo.domain.TripFix

/**
 * How often and how exactly a trip asks for fixes. Far from the stop to
 * get off at, balanced accuracy, which the platform serves from Wi-Fi and
 * cell towers and seldom the GPS; near it, high accuracy, the GPS.
 */
enum class FixQuality(val intervalMillis: Long) {
    BALANCED(15_000),
    HIGH(10_000),
}

/**
 * The device's fixes as a trip follows them, one request at a time.
 * Behind an interface so tests can stand in for the device.
 */
interface LocationUpdates {
    /** Whether fixes can come: location on, and the permission, coarse or fine, still given */
    fun usable(): Boolean

    /**
     * Ask for fixes at [quality] from now on, replacing what was asked
     * before; each comes to [onFix], and [onLost] hears when location goes
     * off. Main thread.
     */
    fun request(quality: FixQuality, onFix: (TripFix) -> Unit, onLost: () -> Unit)

    /** Ask for nothing more; nothing comes after */
    fun stop()

    /** The quality asked for now; null when nothing is */
    val requested: FixQuality?
}

/**
 * [LocationUpdates] on the framework's LocationManager, as the rest of
 * the app reads the location: no Play services. The platform's fused
 * provider where there is one, which takes the request's quality as its
 * hint; elsewhere the GPS for high accuracy and the network for balanced.
 */
class AndroidLocationUpdates(private val context: Context, private val now: () -> Long = System::currentTimeMillis) : LocationUpdates {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListenerCompat? = null

    override var requested: FixQuality? = null
        private set

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun usable(): Boolean {
        val manager = manager ?: return false
        val permitted = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return permitted && LocationManagerCompat.isLocationEnabled(manager)
    }

    // usable() holds before every request
    @SuppressLint("MissingPermission")
    override fun request(quality: FixQuality, onFix: (TripFix) -> Unit, onLost: () -> Unit) {
        stop()
        val manager = manager ?: return onLost()
        if (!usable()) return onLost()
        val provider = provider(manager, quality) ?: return onLost()
        val request = LocationRequestCompat.Builder(quality.intervalMillis)
            .setMinUpdateIntervalMillis(quality.intervalMillis)
            .setQuality(
                when (quality) {
                    FixQuality.BALANCED -> LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
                    FixQuality.HIGH -> LocationRequestCompat.QUALITY_HIGH_ACCURACY
                },
            )
            .build()
        val mine = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                if (listener !== this) return
                val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.MAX_VALUE
                onFix(TripFix(GeoPoint(location.latitude, location.longitude), accuracy, now()))
            }

            override fun onProviderDisabled(disabled: String) {
                if (listener === this && disabled == provider) onLost()
            }
        }
        listener = mine
        requested = quality
        try {
            LocationManagerCompat.requestLocationUpdates(manager, provider, request, ContextCompat.getMainExecutor(context), mine)
        } catch (_: SecurityException) {
            // Taken back between the check and the request
            stop()
            onLost()
        }
    }

    // Taking a listener off needs no permission, and must work once it is gone
    @SuppressLint("MissingPermission")
    override fun stop() {
        val mine = listener ?: return
        listener = null
        requested = null
        manager?.let { LocationManagerCompat.removeUpdates(it, mine) }
    }

    private fun provider(manager: LocationManager, quality: FixQuality): String? {
        val enabled = manager.getProviders(true)
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val gps = fine && LocationManager.GPS_PROVIDER in enabled
        val network = LocationManager.NETWORK_PROVIDER in enabled
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in enabled -> LocationManager.FUSED_PROVIDER
            quality == FixQuality.HIGH && gps -> LocationManager.GPS_PROVIDER
            network -> LocationManager.NETWORK_PROVIDER
            gps -> LocationManager.GPS_PROVIDER
            else -> null
        }
    }
}
