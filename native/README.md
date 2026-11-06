# CaliGo native

The Kotlin and Jetpack Compose port of CaliGo. It installs as "CaliGo Next"
(`com.yenreh.caligo.next`) beside the Flutter app until it replaces it.

## Requirements

- A full JDK 21 (with `javac`) as `JAVA_HOME`; a JRE alone fails at the
  Java compile tasks. Android Studio's bundled `jbr` works.
- The Android SDK, named by `sdk.dir` in `local.properties`, with the
  platform `android-37.2`.

## Commands

```sh
./gradlew assembleRelease   # R8 and resource shrinking, debug key for now
./gradlew lint
./gradlew test              # JVM tests on Robolectric, screenshots included
./gradlew clean assembleRelease lint test --warning-mode=fail   # what review runs
```

`test` records the screenshots to `app/build/outputs/roborazzi/`. The
release APK is `app/build/outputs/apk/release/app-release.apk`.

Every version lives in `gradle/libs.versions.toml`, SDK levels included.

## Data

The database (`caligo.db`), `settings.json` and the cache sit where the
Flutter app keeps them, in the same formats, so the final port reads that
app's data as is. A debug build takes a backup exported by the Flutter app
(Settings, Export data) on launch:

```sh
adb push caligo_backup.json /sdcard/Android/data/com.yenreh.caligo.next/files/import.json
```

It is imported once and renamed to `import.done.json`; one that fails to
import stays, and is tried again on the next launch.
