@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yenreh.caligo.data

import com.yenreh.caligo.data.json.JsonTypeException
import com.yenreh.caligo.data.json.parseJson
import com.yenreh.caligo.domain.entities.AppThemeMode
import com.yenreh.caligo.domain.entities.HomeContent
import com.yenreh.caligo.domain.entities.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** settings_provider.dart: the settings.json the Flutter app keeps */
class SettingsStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file get() = folder.root.resolve("settings.json")

    private fun TestScope.store(): SettingsStore {
        val store = SettingsStore(file, this)
        advanceUntilIdle()
        return store
    }

    @Test
    fun startsOnTheDefaultsWithoutAFile() = runTest {
        assertEquals(Settings(isLoading = false), store().settings.value)
        assertFalse(file.exists())
    }

    @Test
    fun readsTheFileTheFlutterAppWrote() = runTest {
        file.writeText(
            """{"themeMode":2,"farePrice":3600,"homeContent":2,"sortStopsByProximity":false,""" +
                """"darkMap":true,"sharpMap":false,"refreshBalancesOnOpen":false}""",
        )

        assertEquals(
            Settings(
                themeMode = AppThemeMode.DARK,
                farePrice = 3600,
                homeContent = HomeContent.BOTH,
                sortStopsByProximity = false,
                darkMap = true,
                sharpMap = false,
                refreshBalancesOnOpen = false,
                isLoading = false,
            ),
            store().settings.value,
        )
    }

    @Test
    fun keepsTheAlertsAndTakesThemOffWhenAFileLacksThem() = runTest {
        file.writeText("""{"alertSound":true,"alertVibrate":true}""")
        assertEquals(Settings(alertSound = true, alertVibrate = true, isLoading = false), store().settings.value)
        // The Flutter app's file has none: both off
        file.writeText("""{"themeMode":0}""")
        val store = store()
        assertFalse(store.settings.value.alertSound)
        assertFalse(store.settings.value.alertVibrate)
        store.setAlertVibrate(true)
        advanceUntilIdle()
        assertTrue(file.readText().endsWith(""""alertSound":false,"alertVibrate":true}"""))
    }

    @Test
    fun aFileThatCannotBeReadLeavesTheDefaults() = runTest {
        for (text in listOf("not json", """{"themeMode": 3}""", """{"farePrice": "3500"}""", "[]")) {
            file.writeText(text)
            assertEquals(text, Settings(isLoading = false), store().settings.value)
        }
    }

    @Test
    fun savesEachChangeAsTheFlutterAppDid() = runTest {
        val store = store()

        store.setHomeContent(HomeContent.STOPS)
        store.setFarePrice(0)
        store.setFarePrice(3700)
        advanceUntilIdle()

        assertEquals(
            """{"themeMode":0,"farePrice":3700,"homeContent":1,"sortStopsByProximity":true,""" +
                """"darkMap":false,"sharpMap":true,"refreshBalancesOnOpen":true,""" +
                """"alertSound":false,"alertVibrate":false}""",
            file.readText(),
        )
        assertTrue(store.settings.value.showsStops)
        assertFalse(store.settings.value.showsCards)
    }

    @Test
    fun importTakesTheDefaultsForWhatTheBackupLacks() = runTest {
        val store = store()
        store.setDarkMap(true)

        store.importSettings(parseJson("""{"farePrice": 3800}""") as JsonObject)

        assertEquals(Settings(farePrice = 3800, isLoading = false), store.settings.value)
        assertEquals(3800, (parseJson(file.readText()) as JsonObject)["farePrice"].toString().toInt())
    }

    @Test
    fun importRefusesAValueOfTheWrongType() = runTest {
        val store = store()

        try {
            store.importSettings(parseJson("""{"darkMap": "yes"}""") as JsonObject)
            throw AssertionError("Expected the import to fail")
        } catch (_: JsonTypeException) {
        }
        assertEquals(Settings(isLoading = false), store.settings.value)
    }

    @Test
    fun countsTheFaresABalanceCovers() {
        val settings = Settings(farePrice = 3500)
        assertEquals(4, settings.calculateFares(15300.0))
        assertEquals(0, settings.calculateFares(null))
        assertEquals(0, settings.calculateFares(-100.0))
    }
}
