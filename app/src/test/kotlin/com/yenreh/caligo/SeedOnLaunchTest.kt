package com.yenreh.caligo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The debug hook that seeds the phone from an `adb push`ed backup */
@RunWith(AndroidJUnit4::class)
class SeedOnLaunchTest {
    private val app: CaliGoApp = ApplicationProvider.getApplicationContext()
    private val folder = app.getExternalFilesDir(null)!!

    @Test
    fun importsThePushedBackupOnceAndMarksItDone() = runBlocking {
        val pushed = File(folder, "import.json")
        pushed.writeText(
            """{"version": 4, "cards": [{"internalId": "x", "id": "06051868", "name": "Mine"}],
               "favoriteStops": [{"id": "500800", "name": "Plaza de Cayzedo A1",
                                  "anchorLatitude": 3.4516, "anchorLongitude": -76.532}]}""",
        )

        seedOnLaunch(app)
        app.container.startup.join()
        seedOnLaunch(app)
        app.container.startup.join()

        assertFalse(pushed.exists())
        assertTrue(File(folder, "import.done.json").exists())
        assertEquals(listOf("Mine"), app.container.cardRepository.getAllCards().map { it.name })
        assertEquals(1, app.container.stopsRepository.getFavorites().size)
    }

    @Test
    fun aBackupThatFailsStaysForTheNextLaunch() = runBlocking {
        val pushed = File(folder, "import.json")
        pushed.writeText("not json")

        seedOnLaunch(app)
        app.container.startup.join()

        assertTrue(pushed.exists())
        assertFalse(File(folder, "import.done.json").exists())
    }

    @Test
    fun withoutABackupNothingWaits() {
        seedOnLaunch(app)

        assertTrue(app.container.startup.isCompleted)
    }
}
