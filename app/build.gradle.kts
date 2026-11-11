import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.HostTestBuilder
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.baselineprofile)
}

/**
 * The CARTO key the map tiles ask with: the CARTO_KEY environment
 * variable, else carto.key in local.properties, which git ignores. Never
 * committed; without one the maps show OpenStreetMap's tiles.
 */
val cartoKey: String = providers.environmentVariable("CARTO_KEY").orNull?.takeIf { it.isNotBlank() }
    ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }.getProperty("carto.key")
    }?.trim()
    ?: ""

/**
 * Trip tracking, still experimental: built only with -Ptrips=true, which
 * neither CI nor the release workflow passes. Off, the app is 5.1's waits
 * and planner with no trip anywhere, and the release manifest drops the
 * trip service and its location permission.
 */
val trips: Boolean = providers.gradleProperty("trips").orNull?.trim() == "true"

/** The version every release is named by, from VERSION at the repository root */
val appVersionName: String = providers.fileContents(rootProject.layout.projectDirectory.file("VERSION"))
    .asText.get().trim()

/**
 * Above every Flutter release's (ABI x 1000 + the workflow run: 2008 for
 * 4.6.0), so this app installs over them: 100000 plus the release
 * workflow's run number, 100000 when built locally
 */
val appVersionCode: Int = 100_000 + (providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 0)

/**
 * The release signing key, described by a key.properties never committed:
 * key.properties at the repository root, which the release workflow writes
 * from its secrets, or else private/signing/key.properties, next to the
 * key, for local release builds. Its storeFile is relative to the file
 * naming it. With neither, release builds are signed with the debug key.
 */
val keystorePropertiesFile: File? = listOf(
    rootProject.file("key.properties"),
    rootProject.file("private/signing/key.properties"),
).firstOrNull { it.isFile }
val keystoreProperties = Properties().apply { keystorePropertiesFile?.inputStream()?.use(::load) }

fun keystoreProperty(name: String): String =
    requireNotNull(keystoreProperties.getProperty(name)?.trim()) { "$name is missing from $keystorePropertiesFile" }

android {
    namespace = "com.yenreh.caligo"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileMinorApi.get().toInt()
        }
    }

    defaultConfig {
        applicationId = "com.yenreh.caligo"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName + if (trips) "+trips" else ""
        buildConfigField("String", "CARTO_KEY", "\"$cartoKey\"")
        buildConfigField("boolean", "TRIPS", trips.toString())
    }

    if (!trips) {
        // Tests run on debug, which keeps the trip service declared for the
        // trip suites to force the switch on
        sourceSets.getByName("release").manifest.srcFile("src/withoutTrips/AndroidManifest.xml")
    }

    signingConfigs {
        if (keystorePropertiesFile != null) {
            create("release") {
                storeFile = keystorePropertiesFile.parentFile.resolve(keystoreProperty("storeFile"))
                storePassword = keystoreProperty("storePassword")
                keyAlias = keystoreProperty("keyAlias")
                keyPassword = keystoreProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Without the key a local release build still runs, on the debug
            // key; published releases always carry the real one, or they
            // stop installing over each other
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        // The version the settings show and compare with the latest
        // release, and the map key
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        error += setOf(
            "OldTargetApi",
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "ObsoleteSdkInt",
        )
    }
}

// Host tests run on debug alone: the release builds, and the two the
// Baseline Profile plugin adds, differ only in R8, which they do not
// exercise, and the test activity lives in the debug manifest
androidComponents {
    beforeVariants { variant ->
        if (variant.buildType != "debug") {
            variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
        }
    }
}

/** The merged release manifest declares the trip service and its permission only when trips are built */
abstract class ReleaseManifestCheck : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val trips: Property<Boolean>

    @TaskAction
    fun check() {
        val text = manifest.get().asFile.readText()
        for (name in listOf(".trips.TripService\"", "android.permission.FOREGROUND_SERVICE_LOCATION\"")) {
            check((name in text) == trips.get()) {
                "${if (trips.get()) "Missing" else "Declared without trips"}: $name in ${manifest.get().asFile}"
            }
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val built = trips
        val check = tasks.register<ReleaseManifestCheck>("check${variant.name.replaceFirstChar(Char::uppercase)}TripsManifest") {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            trips.set(built)
        }
        // Run with the tests, and before every release APK
        tasks.named { it == "test" || it == "assemble${variant.name.replaceFirstChar(Char::uppercase)}" }.configureEach { dependsOn(check) }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        allWarningsAsErrors = true
    }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    // Robolectric's Android 17 runtime reaches into the JDK's FileDescriptor
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    // Real rendering for every test, fonts and vectors as on a device, and
    // one Robolectric sandbox for all of them
    systemProperty("robolectric.graphicsMode", "NATIVE")
}

// The licenses page lists every library the APK ships, from this report
aboutLibraries {
    // No network at build time
    offlineMode = true
    collect {
        // BOMs only pin versions; nothing of them ships
        includePlatform = false
        // Offline, the plugin fills in no licence text: the texts every
        // library here needs are kept in config/licenses, by SPDX id
        configPath = file("config")
    }
    export {
        // Only what the licenses page shows
        excludeFields.addAll("description", "developers", "funding", "organization", "scm", "website")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    // Installs the Baseline Profile on devices without Play's profile cloud
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // The maps: raster tiles drawn by Compose itself, no native code
    implementation(libs.mapcompose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // The activity createComposeRule hosts. AGP hands Robolectric the app's
    // manifest alone, so a test-only declaration would not reach it
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // ui-test brings Espresso 3.5, whose input injection fails on Android 17
    testImplementation(libs.espresso.core)
    testImplementation(libs.roborazzi)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
}
