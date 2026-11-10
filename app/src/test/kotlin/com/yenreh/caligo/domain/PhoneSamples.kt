package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.BusArrival

/**
 * Answers sampled on the phone on 2026-11-10 for stop 514208, "Cl 121
 * con Kr 26P", every 30 s: the time of each answer, then each bus as
 * line, vehicle and minutes away. Vehicle A47 261001 counts down to +2.1
 * min, then reads +46.8 in the next answer without leaving the list: the
 * service reuses its id for its next pass, one loop later.
 */
object PhoneSamples {
    const val STOP = "514208"
    const val STOP_NAME = "Cl 121 con Kr 26P"

    /** 2026-11-10 00:00 in Cali (UTC-5), epoch milliseconds */
    private const val DAY = 1_794_286_800_000L

    private val LINES = """
        14:42:09 T52:1185001:+3.4 A47:261001:+6.1 T52:1126001:+17.2 A47:939001:+21.5 A47:815001:+36.3
        14:42:39 A47:261001:+5.6 T52:1126001:+16.7 A47:939001:+21.5 A47:815001:+35.8 T52:1073001:+42.8
        14:43:09 A47:261001:+5.1 T52:1126001:+16.2 A47:939001:+21.6 A47:815001:+35.3 T52:1073001:+42.3
        14:43:39 A47:261001:+4.6 T52:1126001:+15.7 A47:939001:+21.4 A47:815001:+34.8 T52:1073001:+41.8
        14:44:09 A47:261001:+4.1 T52:1126001:+15.2 A47:939001:+20.6 A47:815001:+34.3 T52:1073001:+41.3
        14:44:39 A47:261001:+3.6 T52:1126001:+14.7 A47:939001:+20.6 A47:815001:+33.8 T52:1073001:+40.8
        14:45:09 A47:261001:+3.1 T52:1126001:+14.2 A47:939001:+20.5 A47:815001:+33.3 T52:1073001:+40.3
        14:45:39 A47:261001:+2.6 T52:1126001:+13.7 A47:939001:+19.8 A47:815001:+32.8 T52:1073001:+39.8
        14:46:09 A47:261001:+2.1 T52:1126001:+13.2 A47:939001:+19.6 A47:815001:+32.3 T52:1073001:+39.3
        14:46:39 T52:1126001:+12.7 A47:939001:+18.7 A47:815001:+31.8 T52:1073001:+38.8 A47:261001:+46.8
        14:47:09 T52:1126001:+12.2 A47:939001:+18.4 A47:815001:+31.3 T52:1073001:+38.3 A47:261001:+46.3
        14:47:39 T52:1126001:+11.7 A47:939001:+17.8 A47:815001:+30.8 T52:1073001:+37.8 A47:261001:+45.8
        14:48:09 T52:1126001:+11.2 A47:939001:+17.6 A47:815001:+30.3 T52:1073001:+37.3 A47:261001:+45.3
        14:48:39 T52:1126001:+10.7 A47:939001:+16.5 A47:815001:+29.9 T52:1073001:+36.8 A47:261001:+44.8
        14:49:09 T52:1126001:+10.2 A47:939001:+16.4 A47:815001:+29.3 T52:1073001:+36.3 A47:261001:+44.3
        14:49:39 T52:1126001:+9.7 A47:939001:+15.8 A47:815001:+28.8 T52:1073001:+35.8 A47:261001:+43.8
        14:50:09 T52:1126001:+9.2 A47:939001:+15.7 A47:815001:+28.3 T52:1073001:+35.3 A47:261001:+43.3
        14:50:39 T52:1126001:+8.7 A47:939001:+15.1 A47:815001:+27.8 T52:1073001:+34.8 A47:261001:+42.8
        14:51:09 T52:1126001:+8.2 A47:939001:+14.3 A47:815001:+27.3 T52:1073001:+34.3 A47:261001:+42.3
    """.trimIndent()

    /** An answer: when it was fetched, and the stop's buses */
    class Answer(val fetchedAt: Long, val buses: List<BusArrival>)

    val answers: List<Answer> = LINES.lines().map { line ->
        val parts = line.trim().split(" ")
        val fetchedAt = time(parts[0])
        Answer(
            fetchedAt,
            parts.drop(1).map { entry ->
                val (busLine, vehicle, minutes) = entry.split(":")
                BusArrival(
                    line = busLine,
                    destination = if (busLine == "A47") "Valle Grande" else "Universidades",
                    arrivalTime = fetchedAt + (minutes.toDouble() * 60_000).toLong(),
                    vehicleId = vehicle,
                    stopName = STOP_NAME,
                )
            },
        )
    }

    /** "HH:mm:ss" on the sampled day, epoch milliseconds */
    fun time(clock: String): Long {
        val (h, m, s) = clock.split(":").map { it.toLong() }
        return DAY + ((h * 60 + m) * 60 + s) * 1000
    }

    /** The answers fetched up to [clock], fed in order */
    fun fedUpTo(clock: String): FadingArrivals = answers.filter { it.fetchedAt <= time(clock) }
        .fold(FadingArrivals.EMPTY) { fading, answer -> fading.fed(STOP, answer.buses, answer.fetchedAt) }
}
