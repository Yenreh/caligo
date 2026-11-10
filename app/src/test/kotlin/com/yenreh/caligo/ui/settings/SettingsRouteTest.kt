package com.yenreh.caligo.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.WAIT_TIMEOUT_MS
import com.yenreh.caligo.data.MockService.Companion.ok
import com.yenreh.caligo.data.MockService.Companion.status
import com.yenreh.caligo.domain.entities.AppThemeMode
import com.yenreh.caligo.ui.theme.CaliGoTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The settings screen's own actions, with the system's parts real where
 * the JVM has them: the backup goes out through the FileProvider and the
 * share sheet, and comes in through a document picker that answers with
 * [picked] at once
 */
@RunWith(AndroidJUnit4::class)
class SettingsRouteTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val devices = mutableListOf<SettingsTestDevice>()

    @After
    fun close() = devices.forEach { it.close() }

    private fun device(release: MockResponse = status(404)) =
        SettingsTestDevice(folder.newFolder(), { release }).also { devices.add(it) }

    /** What the picker was asked for, each time it opened */
    private val pickerInputs = mutableListOf<Any?>()
    private var picked: Uri? = null

    private val picker = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            pickerInputs.add(input)
            dispatchResult(requestCode, picked)
        }
    }

    /** Every link the screen opened */
    private val opened = mutableListOf<String>()

    private fun show(device: SettingsTestDevice, appVersion: String = "4.1.0") {
        val model = device.viewModel(appVersion, files = AndroidBackupFiles(app))
        compose.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = picker
                },
                LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) {
                        opened.add(uri)
                    }
                },
            ) {
                CaliGoTheme { SettingsRoute(onBack = {}, onLicenses = {}, viewModel = model) }
            }
        }
    }

    private fun tap(text: String) {
        compose.onNodeWithText(text).performScrollTo().performClick()
    }

    private fun waitForText(text: String) = compose.waitUntil(WAIT_TIMEOUT_MS) {
        compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    // Import

    @Test
    fun importsTheDocumentThePickerReturns() {
        val device = device()
        picked = Uri.fromFile(File(folder.root, "caligo_backup.json").apply { writeText(SettingsTestDevice.BACKUP) })
        show(device)

        tap("Import data")

        waitForText("2 items imported")
        assertEquals(listOf("application/json"), pickerInputs.map { (it as Array<*>).toList() }.single())
        assertEquals(listOf("06051868"), runBlocking { device.cards.getAllCards() }.map { it.id })
        assertEquals(AppThemeMode.DARK, device.store.settings.value.themeMode)
    }

    @Test
    fun theFareShownFollowsAnImport() {
        val device = device()
        picked = Uri.fromFile(File(folder.root, "caligo_backup.json").apply { writeText(SettingsTestDevice.BACKUP) })
        show(device)

        tap("Import data")

        waitForText("2 items imported")
        assertEquals(3700, device.store.settings.value.farePrice)
        // The Flutter field kept the fare it opened with
        val fare = compose.onNodeWithTag("fare_price").fetchSemanticsNode().config[SemanticsProperties.EditableText]
        assertEquals("3700", fare.text)
    }

    @Test
    fun leavingThePickerSaysNothing() {
        picked = null
        show(device())

        tap("Import data")
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Nothing new to import").assertDoesNotExist()
        compose.onNodeWithText("Import error").assertDoesNotExist()
    }

    @Test
    fun aDocumentThatIsNotABackupIsAnImportError() {
        val device = device()
        picked = Uri.fromFile(File(folder.root, "notes.json").apply { writeText("not json") })
        show(device)

        tap("Import data")

        waitForText("Import error")
    }

    // Export

    @Test
    fun exportsThroughTheShareSheet() {
        val device = device()
        runBlocking { device.cards.createCard(SettingsTestDevice.card) }
        show(device)

        tap("Export data")

        val started = shadowOf(app)
        compose.waitUntil(WAIT_TIMEOUT_MS) { started.peekNextStartedActivity() != null }
        val chooser = started.nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/json", send.type)
        assertEquals("CaliGo Backup", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.files", uri.authority)
        assertTrue(uri.path!!.endsWith("/caligo_backup.json"))
        val text = app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertTrue(text.startsWith("{\n  \"version\": 4,"))
        assertTrue("\"id\": \"06051868\"" in text)
    }

    @Test
    fun exportingNothingSaysSo() {
        show(device())

        tap("Export data")

        waitForText("There is nothing to export")
        assertEquals(null, shadowOf(app).peekNextStartedActivity())
    }

    // The rest of the screen

    @Test
    fun savesTheFarePriceTyped() {
        val device = device()
        show(device)

        compose.onNodeWithTag("fare_price").performScrollTo().performTextReplacement("4200")
        tap("Save")

        waitForText("Fare price updated")
        assertEquals(4200, device.store.settings.value.farePrice)
    }

    @Test
    fun eachRowChangesItsSetting() {
        val device = device()
        show(device)

        tap("Dark")
        tap("Favorite stops only")
        tap("Dark map")
        tap("Refresh balances on opening")
        tap("Sound when the bus is near")
        tap("Vibrate")
        compose.waitForIdle()

        val settings = device.store.settings.value
        assertEquals(AppThemeMode.DARK, settings.themeMode)
        assertEquals(com.yenreh.caligo.domain.entities.HomeContent.STOPS, settings.homeContent)
        assertTrue(settings.darkMap)
        assertEquals(false, settings.refreshBalancesOnOpen)
        assertTrue(settings.alertSound)
        assertTrue(settings.alertVibrate)
    }

    @Test
    fun aNewerReleaseDownloadsItsApkInTheBrowser() {
        show(device(ok(RELEASE)), appVersion = "4.1.0")

        tap("Check for updates")
        waitForText("Download 4.2.0")
        compose.onNodeWithText("Version 4.2.0 is available").assertExists()
        tap("Download 4.2.0")

        assertEquals(listOf("https://github.com/Yenreh/CaliGo/releases/download/v4.2.0/CaliGo-v4.2.0-arm64-v8a.apk"), opened)
    }

    @Test
    fun theAboutLinksOpenTheRepositoryAndTheDataSource() {
        show(device())

        tap("by Yenreh")
        tap("Data: Metro Cali S.A.")

        assertEquals(listOf("https://github.com/Yenreh/CaliGo", "https://www.metrocali.gov.co"), opened)
    }

    private companion object {
        val RELEASE = """
            {"tag_name":"v4.2.0","html_url":"https://github.com/Yenreh/CaliGo/releases/tag/v4.2.0",
             "assets":[{"browser_download_url":"https://github.com/Yenreh/CaliGo/releases/download/v4.2.0/CaliGo-v4.2.0-arm64-v8a.apk"}]}
        """.trimIndent()
    }
}
