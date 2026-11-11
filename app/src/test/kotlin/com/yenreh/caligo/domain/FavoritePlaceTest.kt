package com.yenreh.caligo.domain

import com.yenreh.caligo.domain.entities.FavoritePlace
import com.yenreh.caligo.domain.entities.PlaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A saved place: its name short, trimmed and never empty, and what it is the place of */
class FavoritePlaceTest {
    @Test
    fun aNameIsTrimmedOneSpaceBetweenWordsAndCapped() {
        assertEquals("Casa", FavoritePlace.cleanName("  Casa\n"))
        assertEquals("Casa de mi mamá", FavoritePlace.cleanName("Casa   de\tmi mamá"))
        assertEquals("Universidad del Valle Me", FavoritePlace.cleanName("Universidad del Valle Meléndez"))
        assertEquals(FavoritePlace.MAX_NAME, FavoritePlace.cleanName("x".repeat(40))!!.length)
        // Cut where a space ends it, the space goes too
        assertEquals("Universidad del Valle 1", FavoritePlace.cleanName("Universidad del Valle 1 2"))
        assertNull(FavoritePlace.cleanName(""))
        assertNull(FavoritePlace.cleanName("  \n "))
    }

    @Test
    fun theNameSuggestedKeepsWholeWords() {
        assertEquals("Universidades", FavoritePlace.suggestedName("Universidades"))
        assertEquals("Universidad del Valle", FavoritePlace.suggestedName("Universidad del Valle Meléndez"))
        assertEquals("Cl. 13 #100-00, Cali", FavoritePlace.suggestedName("Cl. 13 #100-00, Cali, Valle del Cauca"))
        assertEquals("x".repeat(FavoritePlace.MAX_NAME), FavoritePlace.suggestedName("x".repeat(30)))
    }

    @Test
    fun aStopIsFoundByItsIdAndAPlaceWithoutOneByWhereItIs() {
        val stop = FavoritePlace(1, "U", PlaceKind.STOP, "Universidades", null, 3.3705, -76.531, "t12", 0)
        assertTrue(stop.isAt(3.38, -76.531, "t12"))
        assertFalse(stop.isAt(3.3705, -76.531, "t13"))
        assertTrue(stop.isAt(3.3706, -76.531, null))

        // Saved without its id, as "Go here" did before: within 40 m
        val noId = stop.copy(stopId = null)
        assertTrue(noId.isAt(3.3706, -76.531, "t12"))
        assertFalse(noId.isAt(3.3710, -76.531, "t12"))
    }

    @Test
    fun aKindReadsBackFromItsKey() {
        for (kind in PlaceKind.entries) assertEquals(kind, PlaceKind.of(kind.key))
        assertEquals(PlaceKind.PLACE, PlaceKind.of("home"))
        assertEquals(PlaceKind.PLACE, PlaceKind.of(null))
    }
}
