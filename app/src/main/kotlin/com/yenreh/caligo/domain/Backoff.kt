package com.yenreh.caligo.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How much longer than its usual pace a request waits after [failures]
 * in a row, timeouts or errors: 30 s after one, 60 s after two, 120 s
 * from three on; none once one answers
 */
fun failureBackoff(failures: Int): Duration = when {
    failures <= 0 -> Duration.ZERO
    failures == 1 -> 30.seconds
    failures == 2 -> 60.seconds
    else -> 120.seconds
}
