package com.yenreh.caligo.data.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** dartIntTryParse against what Dart's `int.tryParse` answers */
class DartNumbersTest {
    @Test
    fun readsWholeNumbersWithSignAndSpaces() {
        assertEquals(3500L, dartIntTryParse("3500"))
        assertEquals(3500L, dartIntTryParse(" 3500\n"))
        assertEquals(-5L, dartIntTryParse("-5"))
        assertEquals(5L, dartIntTryParse("+5"))
        assertEquals(16L, dartIntTryParse("0x10"))
        assertEquals(-16L, dartIntTryParse("-0X10"))
    }

    @Test
    fun anythingElseIsNull() {
        for (text in listOf("", " ", "3.5", "3,500", "1e3", "35 00", "abc", "0x", "99999999999999999999")) {
            assertNull(text, dartIntTryParse(text))
        }
    }
}
