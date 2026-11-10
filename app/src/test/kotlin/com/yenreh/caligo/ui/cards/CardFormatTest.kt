package com.yenreh.caligo.ui.cards

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card panel's figures, as the Flutter app's intl formats print them */
class CardFormatTest {
    @Test
    fun balancesAsIntlPrintsPesosForColombia() {
        // Checked against intl's NumberFormat.currency(locale: 'es_CO', symbol: '$', decimalDigits: 0)
        val expected = mapOf(
            15300.0 to "15.300 $",
            0.0 to "0 $",
            -1200.0 to "-1.200 $",
            2500.5 to "2.501 $",
            -2500.5 to "-2.501 $",
            999.4 to "999 $",
            -0.4 to "-0 $",
            1234567.0 to "1.234.567 $",
        )
        for ((balance, text) in expected) assertEquals("$balance", text, formatBalance(balance))
    }

    @Test
    fun groupsTheNumberInFours() {
        assertEquals("1906 0518 6898 1", formatCardNumber("19 06051868 981"))
        assertEquals("1234", formatCardNumber("1234"))
    }

    @Test
    fun countsWholeFaresOnly() {
        assertEquals(4, availableFares(15300.0, 3500))
        assertEquals(0, availableFares(-100.0, 3500))
        assertEquals(0, availableFares(null, 3500))
        assertEquals(0, availableFares(15300.0, 0))
    }

    @Test
    fun oneMoreFareOwingOnlyWithSomethingLeft() {
        // Four fares and 1.300 left, short of a fifth
        assertTrue(fareOnCredit(15300.0, 3500))
        // Short of a single fare
        assertTrue(fareOnCredit(2400.0, 3500))
        // Nothing left after two
        assertFalse(fareOnCredit(7000.0, 3500))
        assertFalse(fareOnCredit(0.0, 3500))
        // Already owing
        assertFalse(fareOnCredit(-100.0, 3500))
        assertFalse(fareOnCredit(null, 3500))
        assertFalse(fareOnCredit(15300.0, 0))
    }

    @Test
    fun datesAsTheFlutterAppPrintsThem() {
        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Bogota"))
            // 2026-09-28 07:05 in Cali
            assertEquals("28/09/2026 07:05", formatDateTime(1790597100000))
        } finally {
            TimeZone.setDefault(zone)
        }
    }
}
