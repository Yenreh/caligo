package com.yenreh.caligo.ui.licenses

import android.content.Context
import com.yenreh.caligo.AppInfo
import com.yenreh.caligo.R
import com.yenreh.caligo.data.json.listOrNull
import com.yenreh.caligo.data.json.obj
import com.yenreh.caligo.data.json.parseJson
import com.yenreh.caligo.data.json.stringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A package and the license texts that cover it, as Flutter's LicenseRegistry pairs them */
data class PackageLicenses(val name: String, val licenses: List<String>)

/**
 * Every license the app carries: its own GPL and the fonts' OFL, which
 * `main.dart` registers because both ask to travel with the program, and
 * those of the libraries in the APK, which the AboutLibraries Gradle
 * plugin writes to `res/raw/aboutlibraries.json` at build time.
 */
object LicenseCatalog {
    private val lock = Mutex()
    private var loaded: List<PackageLicenses>? = null

    /** Read once per process, off the main thread */
    suspend fun load(context: Context): List<PackageLicenses> = lock.withLock {
        loaded ?: withContext(Dispatchers.IO) {
            val assets = context.assets
            val registered = REGISTERED.map { (name, file) ->
                val text = assets.open(file).bufferedReader().use { it.readText() }
                PackageLicenses(name, listOf(if (name == AppInfo.NAME) appLicense(text) else text))
            }
            val libraries = context.resources.openRawResource(R.raw.aboutlibraries)
                .bufferedReader().use { libraries(it.readText()) }
            sorted(registered + libraries)
        }.also { loaded = it }
    }

    /** What `main.dart` registers, package by package */
    private val REGISTERED = listOf(
        AppInfo.NAME to "licenses/LICENSE.txt",
        "Fraunces" to "licenses/OFL-Fraunces.txt",
        "IBM Plex Sans" to "licenses/OFL-IBMPlexSans.txt",
        "IBM Plex Mono" to "licenses/OFL-IBMPlexMono.txt",
    )

    /** The GPL text names no one: say whose program it covers */
    internal fun appLicense(gpl: String) =
        "${AppInfo.NAME}\n${AppInfo.COPYRIGHT}\n\n" +
            "Free software under the GNU General Public License, version 3 or any later version.\n\n$gpl"

    /**
     * Each library of an AboutLibraries report, by the name its POM gives
     * it, short as a Dart package's ("Compose UI", "okhttp"), with the full
     * text of each of its licenses, or the license's name and address when
     * the report has no text for it. A library whose POM names it by its
     * coordinate goes by its artifact; two sharing a name, by coordinate.
     */
    internal fun libraries(json: String): List<PackageLicenses> {
        val report = parseJson(json).obj()
        val texts = report["licenses"]?.obj().orEmpty().mapValues { (_, license) ->
            val entry = license.obj()
            entry["content"].stringOrNull()
                ?: listOfNotNull(entry["name"].stringOrNull(), entry["url"].stringOrNull()).joinToString("\n")
        }
        val libraries = report["libraries"].listOrNull().orEmpty().map { item ->
            val library = item.obj()
            val ids = library["licenses"].listOrNull().orEmpty().mapNotNull { it.stringOrNull() }
            val coordinate = library["uniqueId"].stringOrNull().orEmpty()
            Triple(coordinate, readableName(coordinate, library["name"].stringOrNull()), ids.map { texts[it] ?: it })
        }
        val shared = libraries.groupingBy { it.second }.eachCount().filterValues { it > 1 }.keys
        return libraries.map { (coordinate, name, licenses) ->
            PackageLicenses(if (name in shared) coordinate else name, licenses)
        }
    }

    /** The POM's [name], or the artifact of [coordinate] where the name is missing or a coordinate itself */
    private fun readableName(coordinate: String, name: String?): String = when {
        name.isNullOrBlank() || ':' in name -> coordinate.substringAfter(':')
        else -> name.trim()
    }

    /**
     * The app first, then every other package in case-insensitive
     * alphabetical order, as Flutter's license page sorts them
     */
    internal fun sorted(packages: List<PackageLicenses>): List<PackageLicenses> =
        packages.sortedWith { a, b ->
            when {
                a.name == b.name -> 0
                a.name == AppInfo.NAME -> -1
                b.name == AppInfo.NAME -> 1
                else -> a.name.lowercase().compareTo(b.name.lowercase())
            }
        }
}
