package com.yenreh.caligo.data.json

import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/*
 * Dates as the Flutter app wrote them to backups and read them back:
 * Dart's `DateTime.toIso8601String` and `DateTime.tryParse`, in epoch
 * milliseconds. java.time would do, but needs API 26.
 */

private fun calendar(zone: TimeZone) = GregorianCalendar(zone).apply {
    // Dart counts in the proleptic Gregorian calendar, before 1582 too
    gregorianChange = Date(Long.MIN_VALUE)
    isLenient = true
    clear()
}

/** [millis] as local time, the way `toIso8601String` writes a local DateTime */
fun dartIsoString(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val c = calendar(zone).apply { timeInMillis = millis }
    val era = c.get(Calendar.ERA)
    val year = if (era == GregorianCalendar.BC) 1 - c.get(Calendar.YEAR) else c.get(Calendar.YEAR)
    val y = when {
        year in 0..9999 -> "%04d".format(Locale.ROOT, year)
        else -> (if (year < 0) "-" else "+") + "%06d".format(Locale.ROOT, abs(year))
    }
    return "%s-%02d-%02dT%02d:%02d:%02d.%03d".format(
        Locale.ROOT,
        y,
        c.get(Calendar.MONTH) + 1,
        c.get(Calendar.DAY_OF_MONTH),
        c.get(Calendar.HOUR_OF_DAY),
        c.get(Calendar.MINUTE),
        c.get(Calendar.SECOND),
        c.get(Calendar.MILLISECOND),
    )
}

private val dartDate = Regex(
    "^([+-]?\\d{4,6})-?(\\d\\d)-?(\\d\\d)" +
        "(?:[ T](\\d\\d)(?::?(\\d\\d)(?::?(\\d\\d)(?:[.,](\\d+))?)?)?" +
        "( ?[zZ]| ?([-+])(\\d\\d)(?::?(\\d\\d))?)?)?$",
)

/**
 * Epoch milliseconds of [text] as `DateTime.tryParse` reads it, or null
 * when it does not match. Fields past their range roll over, as in Dart;
 * a time without a zone is local.
 */
fun dartTryParse(text: String, zone: TimeZone = TimeZone.getDefault()): Long? {
    val m = dartDate.matchEntire(text) ?: return null
    val g = m.groupValues
    fun field(i: Int) = g[i].ifEmpty { "0" }.toInt()

    val year = g[1].toInt()
    // Up to microseconds; Dart drops the digits after the sixth
    val micros = g[7].takeIf { it.isNotEmpty() }?.take(6)?.padEnd(6, '0')?.toInt() ?: 0
    var minute = field(5)
    val utc = g[8].isNotEmpty()
    if (g[9].isNotEmpty()) {
        val sign = if (g[9] == "-") -1 else 1
        minute -= sign * (field(10) * 60 + field(11))
    }

    val c = calendar(if (utc) TimeZone.getTimeZone("UTC") else zone)
    if (year <= 0) {
        c.set(Calendar.ERA, GregorianCalendar.BC)
        c.set(Calendar.YEAR, 1 - year)
    } else {
        c.set(Calendar.YEAR, year)
    }
    c.set(Calendar.MONTH, g[2].toInt() - 1)
    c.set(Calendar.DAY_OF_MONTH, g[3].toInt())
    c.set(Calendar.HOUR_OF_DAY, field(4))
    c.set(Calendar.MINUTE, minute)
    c.set(Calendar.SECOND, field(6))
    c.set(Calendar.MILLISECOND, micros / 1000)
    return c.timeInMillis
}
