package com.yenreh.caligo.ui.navigation

import androidx.navigation3.runtime.NavKey
import com.yenreh.caligo.domain.entities.TripPlace
import kotlinx.serialization.Serializable

/**
 * Every screen the app can show, one key each: the routes of
 * `lib/presentation/routes/app_router.dart`, the screens the Flutter app
 * pushes with Navigator, and the license pages. Serializable, so the back
 * stack outlives the process.
 */
@Serializable
sealed interface Route : NavKey {
    /** `/`: the home dashboard */
    @Serializable
    data object Home : Route

    /** `/cards` */
    @Serializable
    data object Cards : Route

    /** `/create` */
    @Serializable
    data object CreateCard : Route

    /** `/edit/:cardId` */
    @Serializable
    data class EditCard(val cardId: String) : Route

    /** `/stops` */
    @Serializable
    data object Stops : Route

    /** `/stops/add` */
    @Serializable
    data object AddStop : Route

    /** `/search`: the add-stop screen to look around, saving one option among others */
    @Serializable
    data object SearchStops : Route

    /** `/plan`, or pushed from a stop's details with where to go */
    @Serializable
    data class PlanTrip(val destination: PlaceKey? = null) : Route

    /**
     * Where a trip starts, when [origin], or ends: the planner's place
     * picker, its map opening [near] that end or the other
     */
    @Serializable
    data class PlacePicker(val origin: Boolean, val near: PlaceKey? = null) : Route

    /** The place picker's map with a crosshair, opening [near] that place */
    @Serializable
    data class MapPoint(val near: PlaceKey? = null) : Route

    /** A line's route and buses, pushed from the line list */
    @Serializable
    data class Line(val id: Int, val name: String) : Route

    /** A station's stops, pushed from the station list */
    @Serializable
    data class Station(
        val id: Int,
        val name: String,
        val address: String,
        val latitude: Double,
        val longitude: Double,
    ) : Route

    /** `/settings` */
    @Serializable
    data object Settings : Route

    /** The license page the settings open, as `showLicensePage` */
    @Serializable
    data object Licenses : Route

    /** One package's licenses, by the name the list shows */
    @Serializable
    data class PackageLicense(val name: String) : Route
}

/** A [TripPlace] as a key carries it, saved with the back stack */
@Serializable
data class PlaceKey(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val isDevice: Boolean = false,
) {
    val place: TripPlace get() = TripPlace(latitude, longitude, name, isDevice)
}

val TripPlace.key: PlaceKey get() = PlaceKey(latitude, longitude, name, isDevice)
