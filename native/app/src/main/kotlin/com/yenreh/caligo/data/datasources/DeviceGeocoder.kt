package com.yenreh.caligo.data.datasources

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import com.yenreh.caligo.domain.CALI_BOUNDS
import com.yenreh.caligo.domain.GeoBounds
import com.yenreh.caligo.domain.GeocodedPlace
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * A geocoder of the platform, behind an interface so tests can stand in
 * for the device
 */
interface PlatformGeocoder {
    /** Whether the device has a geocoder at all */
    fun isPresent(): Boolean

    /**
     * At most [maxResults] places named [query] within [bounds], best
     * first. Throws when the service does not answer.
     */
    suspend fun search(query: String, maxResults: Int, bounds: GeoBounds): List<GeocodedPlace>
}

/**
 * Android's own geocoder: Google's, by way of Play services, with no key
 * or quota to manage. Asked in Colombian Spanish, as the Flutter app's
 * bridge to it asked.
 */
class AndroidGeocoder(private val context: Context) : PlatformGeocoder {
    override fun isPresent() = Geocoder.isPresent()

    override suspend fun search(query: String, maxResults: Int, bounds: GeoBounds): List<GeocodedPlace> {
        val geocoder = Geocoder(context, LOCALE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            searchListening(geocoder, query, maxResults, bounds)
        } else {
            // Asked over the network: off the main thread
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, maxResults, bounds.south, bounds.west, bounds.north, bounds.east)
                    .orEmpty()
                    .map(::placeOf)
            }
        }
    }

    /** From Android 13 the geocoder answers a listener, off the main thread */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun searchListening(
        geocoder: Geocoder,
        query: String,
        maxResults: Int,
        bounds: GeoBounds,
    ): List<GeocodedPlace> = suspendCancellableCoroutine { continuation ->
        geocoder.getFromLocationName(
            query,
            maxResults,
            bounds.south,
            bounds.west,
            bounds.north,
            bounds.east,
            object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    continuation.resume(addresses.map(::placeOf))
                }

                override fun onError(errorMessage: String?) {
                    continuation.resumeWithException(IOException(errorMessage))
                }
            },
        )
    }

    companion object {
        private val LOCALE: Locale = Locale.forLanguageTag("es-CO")

        /** A place as the Flutter bridge handed it over: its first address line, or none */
        fun placeOf(address: Address) = GeocodedPlace(
            line = address.getAddressLine(0) ?: "",
            latitude = address.latitude,
            longitude = address.longitude,
        )
    }
}

/**
 * Android's own geocoder, as the Flutter app reached it through its
 * bridge. On a phone without one, or when the service does not answer,
 * it finds nothing and the search falls back on the MIO stop names.
 */
class DeviceGeocoder(private val platform: PlatformGeocoder) {
    /** Places Android finds for [query], within Cali, best first */
    suspend fun search(query: String): List<GeocodedPlace> {
        if (!platform.isPresent()) return emptyList()
        return try {
            platform.search(query, MAX_RESULTS, CALI_BOUNDS)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The service did not answer this time
            emptyList()
        }
    }

    companion object {
        /** As many places as the Flutter bridge asked for */
        const val MAX_RESULTS = 5
    }
}
