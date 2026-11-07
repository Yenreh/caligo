package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.LineStop

/** A box on the map, in degrees */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double)

/** The area searched: Cali, from Pance to Menga and the hills to the river */
val CALI_BOUNDS = GeoBounds(south = 3.30, west = -76.62, north = 3.52, east = -76.44)

/** A place a geocoder found, and how it writes it */
data class GeocodedPlace(val line: String, val latitude: Double, val longitude: Double)

/** How close a match is to what was typed */
enum class AddressPrecision {
    /** The address itself, with the numbers typed */
    EXACT,

    /** A place by its name, as the geocoder knows it */
    PLACE,

    /** The crossing of the address's two streets: within a block of it */
    CROSSING,

    /** The MIO stop named after that crossing: within a block or so too */
    STOP,
}

/** A place found for what was typed */
data class AddressMatch(
    /** What to call it: the address, or the name typed for a place */
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val precision: AddressPrecision,
    /** More about it, such as a place's address; null when the name says it */
    val detail: String? = null,
)

enum class StreetKind { CALLE, CARRERA, AVENIDA, TRANSVERSAL, DIAGONAL }

/**
 * The grids of Cali: the plain one, and the Norte and Oeste of the north
 * and of the western hills
 */
enum class StreetSide { PLAIN, NORTH, WEST, SOUTH }

/**
 * One street of an address: "Calle 26P", "Carrera 28D Bis",
 * "Avenida 3 Norte". Numbers are 64-bit, as Dart's int.
 */
data class StreetRef(
    val kind: StreetKind,
    val number: Long,
    /** The letter after the number, lowercase; empty for none */
    val letter: String = "",
    val bis: Boolean = false,
    val side: StreetSide = StreetSide.PLAIN,
) {
    /** Letter and "bis" together, to tell 26 from 26P or 28D from 28D Bis */
    val mark: String get() = "$letter${if (bis) "bis" else ""}"

    /** As a geocoder is asked for it */
    val spoken: String
        get() {
            val name = when (kind) {
                StreetKind.CALLE -> "Calle"
                StreetKind.CARRERA -> "Carrera"
                StreetKind.AVENIDA -> "Avenida"
                StreetKind.TRANSVERSAL -> "Transversal"
                StreetKind.DIAGONAL -> "Diagonal"
            }
            val grid = when (side) {
                StreetSide.PLAIN -> ""
                StreetSide.NORTH -> " Norte"
                StreetSide.WEST -> " Oeste"
                StreetSide.SOUTH -> " Sur"
            }
            return "$name $number${letter.uppercase()}${if (bis) " Bis" else ""}$grid"
        }
}

/**
 * A Cali address: "Calle 4 # 75-71" stands on Calle 4, 71 m past its
 * crossing with Carrera 75. Typed as a crossing, "Calle 5 con Carrera
 * 38", it has no metres.
 */
data class ParsedAddress(val street: StreetRef, val cross: StreetRef, val meters: Long? = null) {
    /** The crossing of both streets, as a geocoder is asked for it */
    val crossingQuery: String get() = "${street.spoken} & ${cross.spoken}, Cali"

    /**
     * Whether a geocoder's [line] is this address, with its numbers. Where
     * Google has no address ranges for a block it answers with another
     * address that shares a number, even as an exact match, so only these
     * numbers are taken: "Calle 1 # 42-40" once came back "Cl. 1 Oe. #
     * 79A-42".
     */
    fun isAt(line: String): Boolean {
        val found = numbersIn(line) ?: return false
        return found.street == street.number &&
            found.cross.number == cross.number &&
            found.cross.mark == cross.mark &&
            found.meters == meters
    }

    /**
     * Whether a geocoder's [line] is the crossing of both streets, "Cl. 118
     * & Cra. 23", rather than one of them alone
     */
    fun crossesIn(line: String): Boolean {
        val first = line.split(',').first()
        if (!first.contains('&')) return false
        val numbers = DIGITS.findAll(first).mapTo(HashSet()) { it.value.toLong() }
        return street.number in numbers && cross.number in numbers
    }
}

// Dart's RegExp follows JavaScript: `\d` is ASCII, `\s` is its own set of
// spaces and `\b` stands between ASCII word characters. Spelled out, so
// the JVM and Android's ICU engine, Unicode-aware, read them the same.

/** JavaScript's `\s` */
private const val S = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

/** JavaScript's `\d` */
private const val D = "[0-9]"

/** JavaScript's `\b` before a word character */
private const val WORD_START = "(?<![A-Za-z0-9_])"

/** JavaScript's `\b` after a word character */
private const val WORD_END = "(?![A-Za-z0-9_])"

private val DIGITS = Regex("$D+")

/** Street kinds as written, longest first so "cra" is not read as "cr" */
private val KINDS = linkedMapOf(
    "transversal" to StreetKind.TRANSVERSAL,
    "diagonal" to StreetKind.DIAGONAL,
    "carrera" to StreetKind.CARRERA,
    "avenida" to StreetKind.AVENIDA,
    "calle" to StreetKind.CALLE,
    "trans" to StreetKind.TRANSVERSAL,
    "avda" to StreetKind.AVENIDA,
    "diag" to StreetKind.DIAGONAL,
    "cll" to StreetKind.CALLE,
    "cra" to StreetKind.CARRERA,
    "crr" to StreetKind.CARRERA,
    "kra" to StreetKind.CARRERA,
    "av" to StreetKind.AVENIDA,
    "ac" to StreetKind.CALLE,
    "ak" to StreetKind.CARRERA,
    "cl" to StreetKind.CALLE,
    "cr" to StreetKind.CARRERA,
    "kr" to StreetKind.CARRERA,
    "tv" to StreetKind.TRANSVERSAL,
    "tr" to StreetKind.TRANSVERSAL,
    "dg" to StreetKind.DIAGONAL,
    "k" to StreetKind.CARRERA,
)

private val KIND = "(${KINDS.keys.joinToString("|")})\\.?"

/**
 * A street number and what follows it: "26", "26P", "28D Bis", "3N",
 * "44 Norte", "1 Oe."; four groups: number, letters, bis, grid
 */
private val NUMBER =
    "($D+)" +
        "(?:$S*(?!(?:oe|oeste|nte|norte|sur|bis|no|con|y|x|entre)(?![a-z]))" +
        "([a-z]{1,2})(?![a-z]))?" +
        "($S*bis(?![a-z]))?" +
        "(?:$S*(norte|nte|oeste|oe|sur)(?![a-z])\\.?)?"

private val ADDRESS = Regex(
    "^$KIND$S*$NUMBER" +
        "$S*(?:#|no\\.?|n°\\.?|nº\\.?|numero|num\\.?)?$S*" +
        NUMBER +
        "$S*-$S*($D+)",
)

private val CROSSING = Regex(
    "^$KIND$S*$NUMBER" +
        "(?:$S*&$S*|$S+(?:con|y|x|entre)$S+)" +
        "(?:$KIND$S*)?" +
        NUMBER,
)

/**
 * A stop named after a crossing: "Cl 16 entre Kr 100 y 98", "Kr 100 con
 * Cl 16", "Av 3N entre Cl 31 y 30"
 */
private val STOP_NAME = Regex(
    "^$KIND$S*$NUMBER" +
        "$S+(?:entre|con)$S+" +
        "(?:$KIND$S*)?" +
        NUMBER +
        "(?:$S+y$S+" +
        "(?:$KIND$S*)?" +
        NUMBER +
        ")?",
)

/**
 * The street, crossing and metres of an address as a geocoder writes it,
 * anywhere in its line: "Cl. 4 #75-71", "Cra. 100 #5 - 169"
 */
private val WRITTEN = Regex(
    "$WORD_START$KIND$S*$NUMBER" +
        "[^#,]*#$S*(?:no\\.?$S*)?" +
        NUMBER +
        "$S*-$S*($D+)",
)

private val SPACES = Regex("$S+")

private val CALI = Regex("${WORD_START}cali$WORD_END")

/** Whitespace as Dart's String.trim takes it */
private fun Char.isDartWhitespace(): Boolean = when (code) {
    in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
    else -> false
}

/** Dart's String.trim: Kotlin's trims a few other characters too */
internal fun String.dartTrim(): String = trim { it.isDartWhitespace() }

/** Lowercase and without the accents of Spanish: "Estación" is "estacion" */
internal fun foldAccents(text: String): String {
    val from = "áéíóúüñ"
    val to = "aeiouun"
    return buildString {
        for (char in text.lowercase()) {
            val i = from.indexOf(char)
            append(if (i < 0) char else to[i])
        }
    }
}

/**
 * Lowercase, without accents and with one space between words, as the
 * patterns read it: "Cl. 4  #75-71" is "cl. 4 #75-71"
 */
private fun plain(text: String): String = foldAccents(text).replace(SPACES, " ").dartTrim()

private fun MatchResult.group(index: Int): String? = groups[index]?.value

private fun streetAt(m: MatchResult, group: Int, kind: StreetKind): StreetRef {
    var letter = m.group(group + 1) ?: ""
    var side = when (m.group(group + 3)) {
        "norte", "nte" -> StreetSide.NORTH
        "oeste", "oe" -> StreetSide.WEST
        "sur" -> StreetSide.SOUTH
        else -> StreetSide.PLAIN
    }
    // An N after the number is the Norte grid: "3N", "2AN"
    if (side == StreetSide.PLAIN && letter.endsWith('n')) {
        side = StreetSide.NORTH
        letter = letter.dropLast(1)
    }
    return StreetRef(
        kind = kind,
        // As Dart's int.parse: a number past 64 bits throws
        number = m.group(group)!!.toLong(),
        letter = letter,
        bis = m.group(group + 2) != null,
        side = side,
    )
}

/**
 * The street crossing one of [kind]: calles cross carreras, and the
 * avenidas of the north run like carreras
 */
private fun crossKind(kind: StreetKind): StreetKind = when (kind) {
    StreetKind.CALLE, StreetKind.DIAGONAL -> StreetKind.CARRERA
    StreetKind.CARRERA, StreetKind.TRANSVERSAL, StreetKind.AVENIDA -> StreetKind.CALLE
}

/**
 * The crossing street of [street], [cross] as typed: in the Norte grid
 * the crossing streets are Norte too
 */
private fun crossOf(street: StreetRef, cross: StreetRef) = cross.copy(
    side = if (cross.side == StreetSide.PLAIN && street.side == StreetSide.NORTH) StreetSide.NORTH else cross.side,
)

/**
 * A Cali address or crossing read from what was typed; null for anything
 * else, such as the name of a place
 */
fun parseAddress(text: String): ParsedAddress? {
    val plain = plain(text)
    ADDRESS.find(plain)?.let { m ->
        val street = streetAt(m, 2, KINDS.getValue(m.group(1)!!))
        val cross = streetAt(m, 6, crossKind(street.kind))
        return ParsedAddress(street, crossOf(street, cross), m.group(10)!!.toLong())
    }
    CROSSING.find(plain)?.let { m ->
        val street = streetAt(m, 2, KINDS.getValue(m.group(1)!!))
        val cross = streetAt(m, 7, m.group(6)?.let(KINDS::get) ?: crossKind(street.kind))
        return ParsedAddress(street, crossOf(street, cross))
    }
    return null
}

private class WrittenNumbers(val street: Long, val cross: StreetRef, val meters: Long)

private fun numbersIn(line: String): WrittenNumbers? {
    val m = WRITTEN.find(plain(line)) ?: return null
    return WrittenNumbers(
        street = m.group(2)!!.toLong(),
        cross = streetAt(m, 6, StreetKind.CALLE),
        meters = m.group(10)!!.toLong(),
    )
}

/**
 * The MIO stop named after the crossing of [address]'s two streets, the
 * one closest along them when several are
 */
fun stopAtCrossing(address: ParsedAddress, stops: Iterable<LineStop>): LineStop? {
    var best: LineStop? = null
    var bestScore = Double.POSITIVE_INFINITY
    for (stop in stops) {
        val m = STOP_NAME.find(plain(stop.name)) ?: continue
        val on = streetAt(m, 2, KINDS.getValue(m.group(1)!!))
        val crossKind = m.group(6)?.let(KINDS::get) ?: crossKind(on.kind)
        val crossings = buildList {
            add(streetAt(m, 7, crossKind).number)
            if (m.group(12) != null) add(streetAt(m, 12, crossKind).number)
        }

        // On the address's street, or on its crossing street, between the
        // crossings around the other one
        for ((street, other) in listOf(address.street to address.cross, address.cross to address.street)) {
            if (on.kind != street.kind || on.number != street.number || crossKind != other.kind) continue
            val low = crossings.min()
            val high = crossings.max()
            if (other.number < low || other.number > high) continue
            // A letter apart is another street: kept only for want of better
            val score = (if (on.mark == street.mark) 0 else 1000) +
                crossings.minOf { kotlin.math.abs(it - other.number) }.toDouble()
            if (score < bestScore) {
                best = stop
                bestScore = score
            }
        }
    }
    return best
}

/**
 * A geocoder's line without the city and what follows: "Cl. 4 #75-71,
 * Cali, Valle del Cauca, Colombia" is "Cl. 4 #75-71"
 */
fun shortLine(line: String): String {
    val parts = line.split(',').map { it.dartTrim() }
    val city = parts.indexOfFirst { plain(it) == "cali" }
    return (if (city > 0) parts.take(city) else parts.take(2)).joinToString(", ")
}

/**
 * Finds what was typed: an address, a crossing of two streets, or a
 * place by its name.
 *
 * An address is only taken with the numbers typed; failing that, the
 * crossing of its two streets is asked for, and failing that, the MIO
 * stop named after that crossing. Anything else is a wrong place, which
 * is worse than none.
 */
class AddressSearch(
    /** Places a geocoder finds for a query, within Cali, best first */
    private val geocode: suspend (query: String) -> List<GeocodedPlace>,
    /** Stops to fall back on, named after the crossings they stand at */
    private val stops: Iterable<LineStop> = emptyList(),
) {
    suspend fun find(text: String): List<AddressMatch> {
        val typed = text.dartTrim()
        if (typed.isEmpty()) return emptyList()
        // Unbounded by a city, "Cl. 9 #17-40" once landed in Mexico
        val inCali = if (CALI.containsMatchIn(plain(typed))) typed else "$typed, Cali"

        val address = parseAddress(typed)
            ?: return unique(geocode(inCali)).map { place ->
                AddressMatch(
                    name = typed,
                    detail = shortLine(place.line),
                    latitude = place.latitude,
                    longitude = place.longitude,
                    precision = AddressPrecision.PLACE,
                )
            }

        if (address.meters != null) {
            val exact = unique(geocode(inCali)).filter { address.isAt(it.line) }.map { place ->
                AddressMatch(
                    name = shortLine(place.line),
                    latitude = place.latitude,
                    longitude = place.longitude,
                    precision = AddressPrecision.EXACT,
                )
            }
            if (exact.isNotEmpty()) return exact
        }

        for (place in geocode(address.crossingQuery)) {
            if (!address.crossesIn(place.line)) continue
            return listOf(
                AddressMatch(
                    name = shortLine(place.line),
                    latitude = place.latitude,
                    longitude = place.longitude,
                    precision = AddressPrecision.CROSSING,
                ),
            )
        }

        val stop = stopAtCrossing(address, stops) ?: return emptyList()
        return listOf(
            AddressMatch(
                name = stop.name,
                latitude = stop.latitude,
                longitude = stop.longitude,
                precision = AddressPrecision.STOP,
            ),
        )
    }

    private companion object {
        fun unique(places: List<GeocodedPlace>): List<GeocodedPlace> {
            val seen = HashSet<String>()
            return places.filter { seen.add(it.line) }
        }
    }
}
