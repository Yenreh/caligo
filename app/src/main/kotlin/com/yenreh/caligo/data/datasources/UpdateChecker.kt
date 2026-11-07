package com.yenreh.caligo.data.datasources

import android.os.Process
import com.yenreh.caligo.AppInfo
import com.yenreh.caligo.data.json.JsonFormatException
import com.yenreh.caligo.data.json.dartIntTryParse
import com.yenreh.caligo.data.json.parseJson
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient

/** A version published on the repository's releases */
data class AppRelease(
    val version: String,
    /** The release page, with its notes and every APK */
    val page: String,
    /** The APK built for this device, when the release has one */
    val apk: String? = null,
)

/**
 * Asks the repository for its latest release, and only when told to:
 * updates come from GitHub releases, downloaded through the browser, so
 * the app needs no permission to install anything.
 */
class UpdateChecker(
    client: OkHttpClient,
    private val abi: String = deviceAbi(),
) {
    private val client = client.forService(TIMEOUT)

    /** The latest release, or null when none is published yet */
    suspend fun latest(): AppRelease? {
        val response = client.getText(LATEST_URL, HEADERS)

        if (response.statusCode == 404) return null
        if (response.statusCode != 200) {
            throw ServerApiException(
                "Unexpected status: ${response.statusCode}",
                statusCode = response.statusCode,
                isRetryable = false,
            )
        }

        val decoded = try {
            parseJson(response.body)
        } catch (_: JsonFormatException) {
            throw ServerApiException("Unexpected response format")
        }
        val release = decoded as? JsonObject
        val tag = release?.get("tag_name").asString()
        val page = release?.get("html_url").asString()
        if (release == null || tag == null || page == null) throw ServerApiException("Unexpected release response")

        val assets = when (val list = release["assets"]) {
            null, JsonNull -> JsonArray(emptyList())
            is JsonArray -> list
            // Dart's cast fails here with a TypeError, which nothing catches
            else -> throw ServerApiException("Unexpected release response")
        }
        val apk = assets
            .filterIsInstance<JsonObject>()
            .mapNotNull { it["browser_download_url"].asString() }
            .firstOrNull { it.endsWith("-$abi.apk") }

        return AppRelease(version = tag.removePrefix("v"), page = page, apk = apk)
    }

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/${AppInfo.GITHUB_REPO}/releases/latest"
        private val TIMEOUT = 10.seconds
        private val HEADERS = mapOf(
            "Accept" to "application/vnd.github+json",
            "User-Agent" to AppInfo.NAME,
        )

        /**
         * The release workflow builds one APK per ABI; old 32-bit phones
         * take the armeabi-v7a one, everything else arm64-v8a. Like Dart's
         * `Abi.current()`, this asks what the process runs as.
         */
        fun deviceAbi(): String = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"

        /**
         * Whether [candidate] is a later version than [current], comparing
         * the numbers of "major.minor.patch" one by one
         */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(version: String) = version.split('+').first().split('.').map { dartIntTryParse(it) ?: 0L }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until 3) {
                val x = a.getOrElse(i) { 0L }
                val y = b.getOrElse(i) { 0L }
                if (x != y) return x > y
            }
            return false
        }

        private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
