package com.yenreh.caligo.data.datasources

import com.yenreh.caligo.data.MockService
import com.yenreh.caligo.data.MockService.Companion.dropped
import com.yenreh.caligo.data.MockService.Companion.ok
import com.yenreh.caligo.data.MockService.Companion.status
import com.yenreh.caligo.data.assertThrowsSuspend
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** test/update_checker_test.dart, and the answers it leaves out */
class UpdateCheckerTest {
    private val services = mutableListOf<MockService>()

    @After
    fun close() = services.forEach { it.close() }

    private fun checker(response: MockResponse, abi: String = "arm64-v8a"): UpdateChecker {
        val github = MockService { _, _ -> response }.also { services.add(it) }
        return UpdateChecker(github.client, abi = abi)
    }

    // UpdateChecker.latest

    @Test
    fun readsTheVersionAndTheApkBuiltForThisDevice() = runTest {
        val release = checker(ok(RELEASE)).latest()!!

        assertEquals("4.1.0", release.version)
        assertTrue(release.apk!!.endsWith("CaliGo-v4.1.0-arm64-v8a.apk"))
        assertEquals("https://github.com/Yenreh/CaliGo/releases/tag/v4.1.0", release.page)
    }

    @Test
    fun givesOld32BitPhonesTheirOwnApk() = runTest {
        val release = checker(ok(RELEASE), abi = "armeabi-v7a").latest()!!

        assertTrue(release.apk!!.endsWith("-armeabi-v7a.apk"))
    }

    @Test
    fun aRepositoryWithoutReleasesHasNothingToOffer() = runTest {
        assertNull(checker(status(404, "{}")).latest())
    }

    @Test
    fun asksGitHubForTheLatestReleaseAsTheFlutterAppDoes() = runTest {
        checker(ok(RELEASE)).latest()

        val github = services.single()
        assertEquals(listOf("https://api.github.com/repos/Yenreh/CaliGo/releases/latest"), github.requested.map { it.toString() })
        assertEquals("application/vnd.github+json", github.headers.single()["Accept"])
        assertEquals("CaliGo", github.headers.single()["User-Agent"])
    }

    @Test
    fun aReleaseWithoutAnApkForThisDeviceOffersItsPage() = runTest {
        val release = checker(ok("""{"tag_name":"4.2.0","html_url":"https://example.org/r"}""")).latest()!!

        assertEquals("4.2.0", release.version)
        assertNull(release.apk)
    }

    @Test
    fun anotherStatusIsAServerErrorNotRetried() = runTest {
        val error = assertThrowsSuspend<ServerApiException> { checker(status(403)).latest() }

        assertEquals(403, error.statusCode)
        assertFalse(error.isRetryable)
    }

    @Test
    fun anAnswerThatIsNotJsonIsAServerError() = runTest {
        val error = assertThrowsSuspend<ServerApiException> { checker(ok("<html>")).latest() }

        assertEquals("Unexpected response format", error.message)
    }

    @Test
    fun aReleaseWithoutItsFieldsIsAServerError() = runTest {
        for (body in listOf("[]", """{"tag_name":4}""", """{"tag_name":"v1","assets":"none","html_url":"x"}""")) {
            val error = assertThrowsSuspend<ServerApiException> { checker(ok(body)).latest() }
            assertEquals("Unexpected release response", error.message)
        }
    }

    @Test
    fun noConnectionIsANetworkError() = runTest {
        assertThrowsSuspend<NetworkApiException> { checker(dropped()).latest() }
    }

    // UpdateChecker.isNewer

    @Test
    fun comparesEachNumberNotTheText() {
        assertTrue(UpdateChecker.isNewer("4.10.0", "4.9.3"))
        assertTrue(UpdateChecker.isNewer("4.0.1", "4.0.0"))
        assertTrue(UpdateChecker.isNewer("5.0.0", "4.99.99"))
    }

    @Test
    fun theSameOrAnOlderVersionIsNoUpdate() {
        assertFalse(UpdateChecker.isNewer("4.0.0", "4.0.0"))
        assertFalse(UpdateChecker.isNewer("3.9.9", "4.0.0"))
        assertFalse(UpdateChecker.isNewer("4.0.0", "4.0.0+12"))
    }

    @Test
    fun missingOrUnreadablePartsCountAsZero() {
        assertTrue(UpdateChecker.isNewer("4.1", "4.0.9"))
        assertFalse(UpdateChecker.isNewer("4.0", "4.0.0"))
        assertTrue(UpdateChecker.isNewer("4.6.0", ""))
        assertFalse(UpdateChecker.isNewer("4.x.0", "4.0.0"))
    }

    private companion object {
        val RELEASE = """
            {"tag_name":"v4.1.0",
             "html_url":"https://github.com/Yenreh/CaliGo/releases/tag/v4.1.0",
             "assets":[
              {"browser_download_url":"https://github.com/Yenreh/CaliGo/releases/download/v4.1.0/CaliGo-v4.1.0-armeabi-v7a.apk"},
              {"browser_download_url":"https://github.com/Yenreh/CaliGo/releases/download/v4.1.0/CaliGo-v4.1.0-arm64-v8a.apk"}]}
        """.trimIndent()
    }
}
