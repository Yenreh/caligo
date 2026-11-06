package com.yenreh.caligo.domain.entities

import kotlin.math.floor

/** Theme mode options; stored by position */
enum class AppThemeMode { SYSTEM, LIGHT, DARK }

/** What the home screen shows; stored by position */
enum class HomeContent { CARDS, STOPS, BOTH }

/** The user's settings, with the Flutter app's defaults */
data class Settings(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val farePrice: Int = DEFAULT_FARE_PRICE,
    val homeContent: HomeContent = HomeContent.CARDS,
    /**
     * Put the favorite stops closest to the device first; off keeps the
     * order they were saved in
     */
    val sortStopsByProximity: Boolean = true,
    /**
     * Dark map tiles, chosen apart from the app theme: dark tiles are
     * harder to read, so the map stays light unless asked
     */
    val darkMap: Boolean = false,
    /** Map tiles at the screen's full resolution; off saves mobile data */
    val sharpMap: Boolean = true,
    /** Ask for every balance when the app opens */
    val refreshBalancesOnOpen: Boolean = true,
    /** Not stored: true until the saved settings have been read */
    val isLoading: Boolean = true,
) {
    val showsCards: Boolean get() = homeContent != HomeContent.STOPS
    val showsStops: Boolean get() = homeContent != HomeContent.CARDS

    /** How many fares [balance] covers */
    fun calculateFares(balance: Double?): Int {
        if (balance == null || balance <= 0) return 0
        return floor(balance / farePrice).toInt()
    }

    companion object {
        const val DEFAULT_FARE_PRICE = 3500
    }
}
