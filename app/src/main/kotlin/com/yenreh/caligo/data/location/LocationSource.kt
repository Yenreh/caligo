package com.yenreh.caligo.data.location

import com.yenreh.caligo.domain.GeoPoint
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Whether the app may read the location, as geolocator reported it */
enum class LocationPermission {
    /** Not given; asking may still give it */
    DENIED,

    /** Refused for good: only the system settings can give it now */
    DENIED_FOREVER,

    /** Coarse or fine, while in use: the app asks for nothing more */
    GRANTED,
}

/** How exact a fix to ask for, as geolocator's accuracies the app uses */
enum class LocationAccuracy { LOW, HIGH }

/** A position asked for without the permission, as geolocator's PermissionDeniedException */
class LocationPermissionException : Exception("Location permission not granted")

/** Location is off, or no provider can give a fix: geolocator's LocationServiceDisabledException */
class LocationServiceDisabledException : Exception("Location services are disabled")

/** No fix within the time asked: Dart's TimeoutException from geolocator's timeLimit */
class LocationTimeoutException(timeLimit: Duration) : Exception("No position within $timeLimit")

/**
 * The device location, as the Flutter app read it through geolocator: the
 * framework's LocationManager behind it, no Play services. Behind an
 * interface so tests can stand in for the device.
 */
interface LocationSource {
    /** Whether location is on: the GPS or the network provider enabled */
    fun isServiceEnabled(): Boolean

    /** The permission as it stands; never [LocationPermission.DENIED_FOREVER], as geolocator's check */
    fun checkPermission(): LocationPermission

    /**
     * Ask the user through the system prompt, from the activity on screen.
     * [LocationPermission.DENIED_FOREVER] once the system no longer shows it.
     */
    suspend fun requestPermission(): LocationPermission

    /**
     * Where the device was last placed, instantly and without GPS; null when
     * unknown. Throws [LocationPermissionException] without the permission.
     */
    suspend fun lastKnownPosition(): GeoPoint?

    /**
     * A fresh fix at [accuracy], the first the provider gives. Throws
     * [LocationPermissionException], [LocationServiceDisabledException], or
     * [LocationTimeoutException] past [timeLimit].
     */
    suspend fun currentPosition(accuracy: LocationAccuracy, timeLimit: Duration): GeoPoint
}

/** Raised when the device location cannot be read */
class LocationUnavailableException(val permanentlyDenied: Boolean = false) : Exception()

/**
 * Current position.
 *
 * Only asks for the permission when [requestPermission] is set, so a
 * screen can quietly use the location it already has without putting a
 * system prompt in front of someone who just opened it.
 */
suspend fun LocationSource.currentDevicePosition(requestPermission: Boolean = true): GeoPoint {
    if (!isServiceEnabled()) throw LocationUnavailableException()

    var permission = checkPermission()
    if (permission == LocationPermission.DENIED && requestPermission) permission = requestPermission()
    if (permission == LocationPermission.DENIED_FOREVER) throw LocationUnavailableException(permanentlyDenied = true)
    if (permission == LocationPermission.DENIED) throw LocationUnavailableException()

    return currentPosition(LocationAccuracy.HIGH, CURRENT_POSITION_TIME_LIMIT)
}

/**
 * Where the device was last placed, instantly and without GPS; null when
 * unknown or without the permission already given
 */
suspend fun LocationSource.lastKnownDevicePosition(): GeoPoint? = try {
    lastKnownPosition()
} catch (e: Exception) {
    if (e is CancellationException) throw e
    null
}

private val CURRENT_POSITION_TIME_LIMIT = 15.seconds
