@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yenreh.caligo.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.data.MockService.Companion.ok
import com.yenreh.caligo.data.MockService.Companion.status
import com.yenreh.caligo.data.map.TileMetadata
import com.yenreh.caligo.domain.entities.AppThemeMode
import com.yenreh.caligo.domain.entities.HomeContent
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The state and actions of settings_screen.dart, and the backup half of cards_provider.dart */
@RunWith(AndroidJUnit4::class)
class SettingsViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val screens = ViewModelStore()
    private val devices = mutableListOf<SettingsTestDevice>()

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun clear() {
        screens.clear()
        devices.forEach { it.close() }
        Dispatchers.resetMain()
    }

    private fun device(release: MockResponse = status(404)) =
        SettingsTestDevice(folder.newFolder(), { release }).also { devices.add(it) }

    /** Owned by [screens], as a screen owns it, so clearing it is leaving the screen */
    private fun SettingsTestDevice.model(appVersion: String = "4.1.0"): SettingsViewModel =
        ViewModelProvider.create(screens, viewModelFactory { initializer<SettingsViewModel> { this@model.viewModel(appVersion) } })[SettingsViewModel::class]

    /** Already sent once the action's job is done: no waiting, which virtual time would cut short */
    private suspend fun SettingsViewModel.nextMessage() = messages.first()

    // The settings themselves

    @Test
    fun everyChoiceGoesToTheStoredSettings() {
        val device = device()
        val model = device.model()

        model.setThemeMode(AppThemeMode.DARK)
        model.setHomeContent(HomeContent.BOTH)
        model.setSortStopsByProximity(false)
        model.setDarkMap(true)
        model.setSharpMap(false)
        model.setRefreshBalancesOnOpen(false)

        val settings = model.settings.value
        assertEquals(AppThemeMode.DARK, settings.themeMode)
        assertEquals(HomeContent.BOTH, settings.homeContent)
        assertFalse(settings.sortStopsByProximity)
        assertTrue(settings.darkMap)
        assertFalse(settings.sharpMap)
        assertFalse(settings.refreshBalancesOnOpen)
        assertEquals(settings, device.store.settings.value)
    }

    @Test
    fun savesAFarePriceAboveZeroAndSaysSo() = runTest {
        val model = device().model()

        model.saveFarePrice(" 4000 ")

        assertEquals(4000, model.settings.value.farePrice)
        assertEquals(SettingsMessage.FarePriceUpdated, model.nextMessage())
    }

    @Test
    fun ignoresAFarePriceThatIsNotAWholeNumberAboveZero() = runTest {
        val model = device().model()

        for (text in listOf("", "0", "-100", "3.5", "abc", "99999999999")) model.saveFarePrice(text)
        model.saveFarePrice("3600")

        assertEquals(3600, model.settings.value.farePrice)
        // The first message is the valid one's: the others said nothing
        assertEquals(SettingsMessage.FarePriceUpdated, model.nextMessage())
    }

    // The cache

    @Test
    fun showsTheCacheSizeAndClearsIt() = runTest {
        val device = device()
        val model = device.model()
        model.screen.first { it.cacheBytes == 2048L }

        model.clearCache().join()

        assertEquals(1, device.cache.cleared)
        assertEquals(0L, model.screen.value.cacheBytes)
        assertEquals(SettingsMessage.CacheCleared, model.nextMessage())
    }

    @Test
    fun theMapTilesCountAsCachedDataAndGoWithIt() = runTest {
        val device = device()
        val tile = ByteArray(1000) { it.toByte() }
        device.tiles.put("https://tile.openstreetmap.org/16/18835/32139.png", TileMetadata(0, null, null), tile)
        val onDisk = device.tiles.sizeInBytes()
        assertTrue(onDisk > 1000)

        val model = device.model()
        model.screen.first { it.cacheBytes == 2048L + onDisk }

        model.clearCache().join()

        assertEquals(0L, device.tiles.sizeInBytes())
        assertEquals(0L, model.screen.value.cacheBytes)
    }

    // Updates

    @Test
    fun aNewerReleaseIsOfferedWithItsApk() = runTest {
        val model = device(ok(release("v4.2.0"))).model()
        assertNull(model.screen.value.update)

        val check = model.checkForUpdates()
        assertTrue(model.screen.value.checkingUpdates)
        check.join()

        val found = model.screen.value.update as UpdateStatus.Available
        assertEquals("4.2.0", found.release.version)
        assertTrue(found.release.apk!!.endsWith("-arm64-v8a.apk"))
        assertFalse(model.screen.value.checkingUpdates)
    }

    @Test
    fun theSameReleaseSaysThisVersionIsTheLatest() = runTest {
        val model = device(ok(release("v4.1.0"))).model(appVersion = "4.1.0")

        model.checkForUpdates().join()

        assertEquals(UpdateStatus.UpToDate("4.1.0"), model.screen.value.update)
    }

    @Test
    fun noReleaseYetIsSaid() = runTest {
        val model = device(status(404)).model()

        model.checkForUpdates().join()

        assertEquals(UpdateStatus.NoReleases, model.screen.value.update)
    }

    @Test
    fun aFailedCheckSaysSo() = runTest {
        val model = device(status(500)).model()

        model.checkForUpdates().join()

        assertEquals(UpdateStatus.Failed, model.screen.value.update)
        assertFalse(model.screen.value.checkingUpdates)
    }

    // Export

    @Test
    fun exportingNothingSaysThereIsNothingToExport() = runTest {
        val device = device()
        val model = device.model()

        model.export().join()

        assertTrue(device.files.shared.isEmpty())
        assertEquals(SettingsMessage.NothingToExport, model.nextMessage())
    }

    @Test
    fun exportSharesTheBackupAndSaysNothing() = runTest {
        val device = device()
        device.cards.createCard(SettingsTestDevice.card)
        val model = device.model()

        model.export().join()

        val shared = device.files.shared.single()
        assertTrue(shared.startsWith("{\n  \"version\": 4,"))
        assertTrue("\"id\": \"06051868\"" in shared)
        model.saveFarePrice("3600")
        assertEquals(SettingsMessage.FarePriceUpdated, model.nextMessage())
    }

    @Test
    fun aShareThatFailsSaysThereIsNothingToExportAsTheFlutterAppDoes() = runTest {
        val device = device()
        device.cards.createCard(SettingsTestDevice.card)
        device.files.failure = IOException("No space left")
        val model = device.model()

        model.export().join()

        assertEquals(SettingsMessage.NothingToExport, model.nextMessage())
    }

    // Import

    @Test
    fun importsThePickedBackupAndCountsWhatWasNew() = runTest {
        val device = device()
        val uri = Uri.parse("content://picked/backup.json")
        device.files.documents[uri] = SettingsTestDevice.BACKUP
        val model = device.model()
        val reloaded = async(start = CoroutineStart.UNDISPATCHED) { device.backup.imported.first() }

        model.import(uri).join()

        assertEquals(SettingsMessage.Imported(2), model.nextMessage())
        reloaded.await()
        assertEquals(listOf("06051868"), device.cards.getAllCards().map { it.id })
        assertEquals(listOf("500800"), device.stops.getFavorites().map { it.id })
        assertEquals(AppThemeMode.DARK, model.settings.value.themeMode)
        assertEquals(3700, model.settings.value.farePrice)

        model.import(uri).join()
        assertEquals(SettingsMessage.Imported(0), model.nextMessage())
    }

    @Test
    fun leavingThePickerWithoutAFileSaysNothingIsNewAsTheFlutterAppDoes() = runTest {
        val model = device().model()

        model.import(null).join()

        assertEquals(SettingsMessage.Imported(0), model.nextMessage())
    }

    @Test
    fun aFileThatIsNotABackupIsAnImportErrorAndChangesNothing() = runTest {
        val device = device()
        val uri = Uri.parse("content://picked/notes.json")
        device.files.documents[uri] = """{"cards": [{"id": 5}]}"""
        val model = device.model()

        model.import(uri).join()

        assertEquals(SettingsMessage.Imported(-1), model.nextMessage())
        assertTrue(device.cards.getAllCards().isEmpty())
    }

    @Test
    fun aFileThatCannotBeReadIsAnImportError() = runTest {
        val model = device().model()

        model.import(Uri.parse("content://picked/gone.json")).join()

        assertEquals(SettingsMessage.Imported(-1), model.nextMessage())
    }

    @Test
    fun anImportOutlivesTheScreenThatStartedIt() = runTest {
        val device = device()
        val uri = Uri.parse("content://picked/backup.json")
        device.files.documents[uri] = SettingsTestDevice.BACKUP
        device.files.gate = CompletableDeferred()
        val model = device.model()
        val reloaded = async(start = CoroutineStart.UNDISPATCHED) { device.backup.imported.first() }

        model.import(uri)
        screens.clear()
        device.files.gate!!.complete(Unit)

        reloaded.await()
        assertEquals(listOf("06051868"), device.cards.getAllCards().map { it.id })
    }

    private fun release(tag: String) = """
        {"tag_name":"$tag","html_url":"https://github.com/Yenreh/CaliGo/releases/tag/$tag",
         "assets":[{"browser_download_url":"https://github.com/Yenreh/CaliGo/releases/download/$tag/CaliGo-$tag-arm64-v8a.apk"}]}
    """.trimIndent()
}
