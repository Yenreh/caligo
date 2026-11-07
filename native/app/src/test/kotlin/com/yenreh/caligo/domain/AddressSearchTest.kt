package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.LineStop
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** test/address_search_test.dart */
class AddressSearchTest {
    private fun stop(name: String, lat: Double, lon: Double) = LineStop(name, name, lat, lon, 0, 1)

    /** A few stops named after their crossings, as the MIO names them */
    private val stops = listOf(
        stop("Plaza de Cayzedo A1", 3.4513, -76.5331),
        stop("Cl 1 entre Kr 44 y 42", 3.42334, -76.55276),
        stop("Kr 23 entre Cl 118 y 119", 3.42859, -76.46362),
        stop("Av 3N entre Cl 31 y 30", 3.47066, -76.52201),
        stop("Cl 36 entre Kr 148 y 146", 3.31356, -76.52238),
        stop("Kr 100 con Cl 16", 3.37500, -76.53900),
    )

    /** How Google answered each query in the test of 2026-09-29 */
    private val answers = mapOf(
        "Cl. 4 #75-71, Cali" to listOf(
            GeocodedPlace("Cl. 4 #75-71, Cali, Valle del Cauca, Colombia", 3.3899242, -76.5461137),
        ),
        "Carrera 23 # 118-40, Cali" to listOf(
            GeocodedPlace("Cra. 23 # 33B-118, Comuna 8, Cali, Valle del Cauca, Colombia", 3.4368657, -76.510473),
        ),
        "Carrera 23 & Calle 118, Cali" to listOf(
            GeocodedPlace("Cl. 118 & Cra. 23, Puerta Del Sol, Cali, Valle del Cauca, Colombia", 3.42875, -76.46355),
        ),
        "Calle 1 Oeste # 42-40, Cali" to listOf(
            GeocodedPlace("Cl. 1 Oe. # 79A-42, Prados Del Sur, Cali, Valle del Cauca, Colombia", 3.385545, -76.5561649),
        ),
        "Calle 1 Oeste & Carrera 42, Cali" to listOf(
            GeocodedPlace("Cl. 1 Oe., Cali, Valle del Cauca, Colombia", 3.44, -76.60),
        ),
        "Unicentro, Cali" to listOf(
            GeocodedPlace("Cra. 100 #5 - 169, Las Vegas, Cali, Valle del Cauca, Colombia", 3.3740819, -76.5395399),
        ),
    )

    private fun search(asked: MutableList<String>) = AddressSearch(
        geocode = { query ->
            asked.add(query)
            answers[query].orEmpty()
        },
        stops = stops,
    )

    private data class Read(val street: String, val cross: String, val meters: Long?)

    private fun read(text: String): Read? {
        val address = parseAddress(text) ?: return null
        return Read(address.street.spoken, address.cross.spoken, address.meters)
    }

    // Reads a Cali address

    @Test
    fun readsAnAddressInItsUsualSpellings() {
        val expected = Read("Calle 4", "Carrera 75", 71)
        assertEquals(expected, read("Cl. 4 #75-71"))
        assertEquals(expected, read("Calle 4 # 75-71, Cali"))
        assertEquals(expected, read("calle 4 no. 75-71"))
        assertEquals(expected, read("Cll 4 N° 75 - 71"))
        assertEquals(expected, read("Calle 4 75-71"))
        assertEquals(Read("Carrera 23", "Calle 118", 40), read("Kr 23 # 118 - 40"))
        assertEquals("Carrera 50", read("Cra. 50 #50-40")?.street)
    }

    @Test
    fun readsAnAddressWithLettersBisAndTheNorteAndOesteGrids() {
        assertEquals(Read("Calle 26P", "Carrera 72U", 35), read("Calle 26P # 72U-35"))
        assertEquals(Read("Carrera 28D Bis", "Calle 72W", 15), read("Carrera 28D Bis # 72W-15"))
        assertEquals("Calle 1 Oeste", read("Calle 1 Oeste # 42-40")?.street)
        assertEquals("Calle 1 Oeste", read("Cl. 1 Oe. #42-40")?.street)
        // In the Norte grid the crossing streets are Norte too
        val north = Read("Avenida 3 Norte", "Calle 30 Norte", 40)
        assertEquals(north, read("Av. 3 Nte. #30-40"))
        assertEquals(north, read("Avenida 3 Norte # 30N-40"))
        assertEquals(north, read("Av 3N #30N-40"))
        assertEquals("Carrera 2A Norte", read("Calle 30 Norte # 2AN-29")?.cross)
    }

    @Test
    fun readsAnAddressTypedAsACrossing() {
        val crossing = Read("Calle 5", "Carrera 38", null)
        assertEquals(crossing, read("Calle 5 con Carrera 38"))
        assertEquals(crossing, read("cl 5 y kr 38"))
        assertEquals(crossing, read("Calle 5 & Carrera 38"))
    }

    @Test
    fun leavesAPlaceByItsNameAlone() {
        assertNull(read("Unicentro"))
        assertNull(read("Universidad del Valle"))
        assertNull(read("Calle del Arte"))
        assertNull(read("Clínica Imbanaco"))
    }

    // Takes an answer

    @Test
    fun takesAnAnswerOnlyWithTheNumbersAskedFor() {
        fun at(typed: String, line: String) = parseAddress(typed)!!.isAt(line)

        assertTrue(at("Cl. 4 #75-71", "Cl. 4 #75-71, Cali, Valle del Cauca"))
        assertTrue(at("Carrera 100 # 5-169", "Cra. 100 #5 - 169, Las Vegas"))
        assertTrue(at("Carrera 23 # 118-07", "frente al Cali 21, Cra. 23 #118-07, Cali"))
        assertTrue(at("Calle 30 Norte # 2AN-29", "Cl. 30 Nte. #2AN-29 Piso 2 of 312"))
        // What Google answered when it did not know the number
        assertFalse(at("Calle 1 # 42-40", "Cl. 1 Oe. # 79A-42, Prados Del Sur"))
        assertFalse(at("Carrera 23 # 118-40", "Cra. 23 # 33B-118, Comuna 8"))
        assertFalse(at("Avenida 3 Norte # 30-40", "Av. 3 Nte. #30-140, Prados Norte"))
        assertFalse(at("Calle 36 # 146-40", "Cl. 36 #146, Esmeralda"))
    }

    @Test
    fun takesAnAnswerAsACrossingOnlyWhenItNamesBothStreets() {
        val address = parseAddress("Carrera 23 # 118-40")!!

        assertEquals("Carrera 23 & Calle 118, Cali", address.crossingQuery)
        assertTrue(address.crossesIn("Cl. 118 & Cra. 23, Puerta Del Sol"))
        assertFalse(parseAddress("Calle 1 Oeste # 42-40")!!.crossesIn("Cl. 1 Oe., Cali"))
    }

    @Test
    fun findsTheStopNamedAfterACrossing() {
        fun at(typed: String) = stopAtCrossing(parseAddress(typed)!!, stops)?.name

        assertEquals("Cl 1 entre Kr 44 y 42", at("Calle 1 # 42-40"))
        assertEquals("Cl 1 entre Kr 44 y 42", at("Calle 1 Oeste # 42-40"))
        assertEquals("Kr 23 entre Cl 118 y 119", at("Carrera 23 # 118-40"))
        assertEquals("Av 3N entre Cl 31 y 30", at("Avenida 3 Norte # 30-40"))
        assertEquals("Cl 36 entre Kr 148 y 146", at("Calle 36 # 146-40"))
        // Named the other way round: on the crossing street
        assertEquals("Kr 100 con Cl 16", at("Calle 16 # 100-20"))
        assertNull(at("Calle 99 # 1-10"))
    }

    @Test
    fun shortensALineToWhatComesBeforeTheCity() {
        assertEquals("Cl. 4 #75-71", shortLine("Cl. 4 #75-71, Cali, Valle del Cauca, Colombia"))
        assertEquals(
            "Cl. 5 #38-20, San Fernando",
            shortLine("Cl. 5 #38-20, San Fernando, Cali, Valle del Cauca, Colombia"),
        )
    }

    // Searches

    @Test
    fun searchesAnAddressTheGeocoderKnowsAsItIs() = runTest {
        val asked = mutableListOf<String>()
        val found = search(asked).find("Cl. 4 #75-71")

        assertEquals(listOf("Cl. 4 #75-71, Cali"), asked)
        assertEquals(AddressPrecision.EXACT, found.single().precision)
        assertEquals("Cl. 4 #75-71", found.single().name)
    }

    @Test
    fun searchesTheCrossingWhenTheGeocoderGetsTheNumberWrong() = runTest {
        val asked = mutableListOf<String>()
        val found = search(asked).find("Carrera 23 # 118-40")

        assertEquals(listOf("Carrera 23 # 118-40, Cali", "Carrera 23 & Calle 118, Cali"), asked)
        assertEquals(AddressPrecision.CROSSING, found.single().precision)
        assertEquals("Cl. 118 & Cra. 23, Puerta Del Sol", found.single().name)
    }

    @Test
    fun searchesTheStopAtTheCrossingWhenTheGeocoderHasNeither() = runTest {
        val found = search(mutableListOf()).find("Calle 1 Oeste # 42-40")

        assertEquals(AddressPrecision.STOP, found.single().precision)
        assertEquals("Cl 1 entre Kr 44 y 42", found.single().name)
    }

    @Test
    fun searchesAPlaceByItsNameInCali() = runTest {
        val asked = mutableListOf<String>()
        val found = search(asked).find("Unicentro")

        assertEquals(listOf("Unicentro, Cali"), asked)
        assertEquals(AddressPrecision.PLACE, found.single().precision)
        assertEquals("Unicentro", found.single().name)
        assertEquals("Cra. 100 #5 - 169, Las Vegas", found.single().detail)
    }

    @Test
    fun searchesNothingRatherThanAWrongPlace() = runTest {
        assertTrue(search(mutableListOf()).find("Calle 99 # 1-10").isEmpty())
    }

    // Beyond the Dart test: the spaces and digits JavaScript's patterns know

    @Test
    fun readsTheSpacesOfJavaScriptAndOnlyItsAsciiDigits() {
        // A no-break space and a narrow one, as some keyboards and geocoders write them
        assertEquals(Read("Calle 4", "Carrera 75", 71), read("Calle 4 # 75-71"))
        // Arabic-Indic digits are no street number to Dart
        assertNull(read("Calle ٤ # 75-71"))
    }

    @Test
    fun searchesNothingForBlankTextAndKeepsACityTyped() = runTest {
        val asked = mutableListOf<String>()
        assertTrue(search(asked).find(" 　 ").isEmpty())
        search(asked).find("Unicentro Cali")
        assertEquals(listOf("Unicentro Cali"), asked)
    }
}
