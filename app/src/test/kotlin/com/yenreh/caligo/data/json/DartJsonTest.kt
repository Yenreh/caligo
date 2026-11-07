package com.yenreh.caligo.data.json

import java.util.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** JSON and dates read and written as the Flutter app did */
class DartJsonTest {
    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw AssertionError("Expected ${T::class.simpleName}, got $e", e)
        }
        fail("Expected ${T::class.simpleName}, nothing was thrown")
    }

    @Test
    fun takesWhatJsonDecodeTakes() {
        val parsed = parseJson("""{"a": [1, -2.5e3, true, null, "x"], "b": {}}""") as JsonObject
        assertEquals(5, (parsed["a"] as JsonArray).size)
    }

    @Test
    fun refusesWhatJsonDecodeRefuses() {
        for (text in listOf("", "abc", "<html>Service Unavailable</html>", "[abc]", "{a: 1}",
            "[01]", "[1.]", "[NaN]", "[True]", "['x']", "[1,]", "{\"a\": 1} x")) {
            assertThrows<JsonFormatException> { parseJson(text) }
        }
    }

    @Test
    fun castsAsDartDoes() {
        val json = parseJson(
            """{"int": 3500, "double": 3500.0, "text": "3500", "flag": true, "none": null}""",
        ) as JsonObject

        assertEquals(3500, json["int"].intOrNull())
        assertNull(json["none"].intOrNull())
        assertNull(json["missing"].intOrNull())
        assertThrows<JsonTypeException> { json["double"].intOrNull() }
        assertThrows<JsonTypeException> { json["text"].intOrNull() }
        assertThrows<JsonTypeException> { json["flag"].intOrNull() }
        assertEquals(3500.0, json["int"].doubleOrNull()!!, 0.0)
        assertThrows<JsonTypeException> { json["text"].doubleOrNull() }
        assertThrows<JsonTypeException> { json["text"].boolOrNull() }
        assertThrows<JsonTypeException> { json["int"].stringOrNull() }
        assertThrows<JsonTypeException> { json["none"].string() }
        assertEquals(true, json["flag"].boolOrNull())
    }

    @Test
    fun printsValuesAsDartToString() {
        val json = parseJson(
            """{"int": 500800, "double": 119.50, "whole": 3.0, "text": "E21", "list": [1, "a", null],
               "map": {"k": 1}}""",
        ) as JsonObject

        assertEquals("500800", json["int"].dartString())
        assertEquals("119.5", json["double"].dartString())
        assertEquals("3.0", json["whole"].dartString())
        assertEquals("E21", json["text"].dartString())
        assertEquals("[1, a, null]", json["list"].dartString())
        assertEquals("{k: 1}", json["map"].dartString())
        assertNull(json["missing"].dartString())
    }

    @Test
    fun writesDoublesAsDart() {
        assertEquals("3.4516", dartDouble(3.4516))
        assertEquals("-76.532", dartDouble(-76.532))
        assertEquals("1.0", dartDouble(1.0))
        assertEquals("10000000.0", dartDouble(1e7))
        assertEquals("0.00001", dartDouble(1e-5))
        assertEquals("1e-7", dartDouble(1e-7))
        assertEquals("1.5e+21", dartDouble(1.5e21))
        assertEquals("3.452", dartDouble((3.4500 + 3.4540) / 2))
        assertEquals("0.30000000000000004", dartDouble(0.1 + 0.2))
    }

    private val bogota = TimeZone.getTimeZone("America/Bogota")

    @Test
    fun writesLocalTimesAsToIso8601String() {
        // 2026-09-28 10:15:30.123 in Cali, UTC-5
        assertEquals("2026-09-28T10:15:30.123", dartIsoString(1790608530123, bogota))
        assertEquals("1970-01-01T00:00:00.000", dartIsoString(0, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun readsDatesAsTryParse() {
        assertEquals(1790608530123, dartTryParse("2026-09-28T10:15:30.123", bogota))
        assertEquals(1790608530123, dartTryParse("2026-09-28T10:15:30.123456", bogota))
        assertEquals(1790608530123, dartTryParse("2026-09-28T15:15:30.123Z", bogota))
        assertEquals(1790608530123, dartTryParse("2026-09-28 13:15:30.123-02:00", bogota))
        assertEquals(1790571600000, dartTryParse("20260928", bogota))
        // Past the end of the month rolls over, as in Dart
        assertEquals(dartTryParse("2026-10-01", bogota), dartTryParse("2026-09-31", bogota))
        assertNull(dartTryParse("yesterday", bogota))
        assertNull(dartTryParse("2026-9-28", bogota))
    }

    @Test
    fun readsBackWhatItWrites() {
        val now = System.currentTimeMillis()
        assertEquals(now, dartTryParse(dartIsoString(now)))
        assertTrue(dartIsoString(now).matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}""")))
    }
}
