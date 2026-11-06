import com.android.build.api.variant.HostTestBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.yenreh.caligo"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileMinorApi.get().toInt()
        }
    }

    defaultConfig {
        // Installs beside CaliGo until the port replaces it
        applicationId = "com.yenreh.caligo.next"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Debug key until the release key is wired in
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
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

// Host tests run on debug alone: the release build differs only in R8, which
// they do not exercise, and the test activity lives in the debug manifest
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
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

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
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
}
