package com.yenreh.caligo

/**
 * What this build turns on, read from here alone. Tests set it either
 * way: a Robolectric test gets a container of its own, so a change ends
 * with the test.
 */
class AppFeatures(
    /**
     * Trip tracking, experimental: on only in a build with -Ptrips=true.
     * Off, no trip screen, trip service or trip channel is ever reached,
     * and "I took it" ends the wait, as in 5.1.
     */
    var trips: Boolean = BuildConfig.TRIPS,
)
