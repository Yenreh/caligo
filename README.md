# CaliGo

An Android app, in Kotlin and Jetpack Compose, to check the cards, stops
and lines of the MIO, the mass transit system of Cali, Colombia.

> Unofficial app, not affiliated with Metro Cali S.A. The data comes from
> its public services ([metrocali.gov.co](https://www.metrocali.gov.co))
> and may not be accurate. No commercial purpose.

## Download

Get the APK from the [releases](https://github.com/Yenreh/CaliGo/releases).
Since 5.0.0 there is one APK for every phone, published under two names
(`arm64-v8a` and `armeabi-v7a`, the same file) so the Flutter releases up
to 4.6.0 still find it. Every release is signed with the same key, so a
new one installs over the previous one and keeps your data. The app can
look for a newer release from Settings > About, on request only.

## Features

- **Cards**: add, edit, reorder and delete transit cards, with the fares
  their balance covers
- **Balance**: from Metrocali's public service, refreshed on opening the
  app (optional)
- **Favorite stops**: the next buses at each saved stop, with the lines to
  show per stop, which ones appear on the home screen, and their order
  saved or by proximity
- **Map**: look up stops anywhere in the city, on CARTO maps, light or dark
- **Lines**: each line's route, its live buses going each way, and its
  hours
- **Trip planner**: from an address, place, stop or point on the map to
  another, timed with the buses on their way
- **Local storage**: everything stays on the device, in SQLite, with JSON
  export and import
- **Languages**: Spanish and English
- **Design**: the "lab report" style shared with NetSpeedDiag and the
  browser extensions, in light and dark themes

## Stack

- **Language and UI**: Kotlin 2.4 and Jetpack Compose (Material 3), built
  with the Android Gradle Plugin 9 and its built-in Kotlin
- **State**: view models with `StateFlow` and coroutines
- **Database**: the framework's SQLite (`SQLiteOpenHelper`)
- **Networking**: OkHttp, with kotlinx.serialization for JSON
- **Maps**: MapCompose, drawn by Compose itself, on CARTO tiles over
  OpenStreetMap data
- **Location and addresses**: the framework's `LocationManager` and
  `Geocoder`, no Google Play services
- **Navigation**: Navigation 3
- **Type**: Fraunces, IBM Plex Sans and IBM Plex Mono, bundled with the app
- **Tests**: JUnit on Robolectric, with Roborazzi screenshots

## Project layout

```
app/src/main/kotlin/com/yenreh/caligo/
├── data/                    # Data layer
│   ├── datasources/         # Services, database, caches, geocoder
│   ├── json/                # JSON as the Flutter app wrote it
│   ├── location/            # LocationManager behind an interface
│   ├── map/                 # Tile loader and cache
│   └── repositories/        # Implementations
├── domain/                  # Domain layer: entities, interfaces, planner
├── ui/                      # Screens and their view models
│   ├── lab/                 # Components of the lab report style
│   ├── map/                 # LabMap, every map of the app
│   ├── navigation/          # Routes and the back stack
│   ├── theme/               # Colors and type
│   └── cards, home, lines, settings, stops, trips, licenses, common
├── AppContainer.kt          # Everything below the screens, wired by hand
├── AppInfo.kt               # Name, package, repository, data source
└── MainActivity.kt          # Entry point
app/src/test/                # JVM tests and screenshots
app/src/debug/               # Seeding a backup on launch
app/config/licenses/         # License texts for the licenses page
baselineprofile/             # Generator of the Baseline Profile
gradle/libs.versions.toml    # Every version, SDK levels included
```

## Building

It needs a full JDK 21 (with `javac`) as `JAVA_HOME`, where Android
Studio's bundled `jbr` works, and the Android SDK with the platform
`android-37.2`, named in `local.properties`, which git ignores:

```properties
sdk.dir=/path/to/Android/Sdk
carto.key=...
```

```bash
./gradlew test                # JVM tests on Robolectric, screenshots included
./gradlew lint
./gradlew assembleRelease     # R8 and resource shrinking

# Everything, as a review runs it
./gradlew clean assembleRelease lint test --warning-mode=fail
```

The release APK is `app/build/outputs/apk/release/app-release.apk`; the
tests record their screenshots to `app/build/outputs/roborazzi/`. The
version is the one in `VERSION`.

The CARTO key comes from the `CARTO_KEY` environment variable or
`carto.key` in `local.properties`, and is never committed. It is limited
to CaliGo's package signed with its release certificate, which every tile
request names. Without a key the map falls back to OpenStreetMap tiles.

Release builds are signed with the key described by `key.properties`,
never committed either: `key.properties` at the repository root, which
the release workflow writes from its secrets, or
`private/signing/key.properties` next to the key for local builds; its
`storeFile` is relative to the file naming it. Without one, release builds
use the debug key, which does not install over a published release.

Versions are kept on the newest stable releases: Dependabot proposes
updates monthly, and warnings fail the build (Kotlin, Gradle with
`--warning-mode=fail`, and lint on old SDK levels and dependencies).

### Baseline Profile

The `baselineprofile` module cold-starts the app to the home with its
content and scrolls it, to write the profile ART compiles ahead of time.
With a phone connected (Android 13 or later) and the app on it holding
cards and stops (Settings, Import data):

```bash
./gradlew :app:generateBaselineProfile
```

The run builds the app signed as release builds are and installs it over
the one there, so an install signed with another key must go first.
Instrumented runs may uninstall the app when they end: export a backup
before and import it again after. The profile lands in
`app/src/release/generated/baselineProfiles/`, to be committed; the
release build packs it, and `profileinstaller` installs it where no app
store does.

## Releases

Changing `VERSION` on `main` tests, builds, signs and publishes a release,
with the notes in `docs/releases/<version>.md` when that file exists. Its
versionCode is 100000 plus the workflow's run number, above every Flutter
release's; a local build gets 100000. The workflow needs the
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS` and
`CARTO_KEY` secrets, and stops without the signing ones.

## Data

All the data comes from public services of Metro Cali S.A., which allows
reusing its information for informative, non-commercial ends, citing the
source with a link to [metrocali.gov.co](https://www.metrocali.gov.co).
Which services the app reads, and how, is in [docs/api.md](docs/api.md).

On the phone, the database (`caligo.db`), `settings.json` and the cache
(map tiles included) sit where the Flutter app kept them, in the same
formats, so 5.0.0 installed over 4.6.0 keeps everything. Settings, Export
data hands out a JSON backup through the share sheet, and Import data
takes one, all or nothing, through the system's file picker. A debug
build also imports one pushed before launch, once, renaming it to
`import.done.json`:

```bash
adb push caligo_backup.json /sdcard/Android/data/com.yenreh.caligo/files/import.json
```

## License

CaliGo is free software under the [GNU GPL v3 or later](LICENSE): anyone
may use, study, modify and redistribute it, as long as they keep this
notice and whatever they publish with this code stays free, with its
source available under the same license.

```
CaliGo
Copyright (C) 2026 Yenreh

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
```

The fonts (Fraunces, IBM Plex Sans and Mono) carry their own OFL license,
in `app/src/main/assets/licenses`, and the app's licenses page lists every
library it ships. Maps © OpenStreetMap contributors and © CARTO; the
transit data belongs to Metro Cali S.A.

The Flutter app, up to 4.6.0, stays in the history: the `v4.6.0` tag
marks its last version, where the Dart files the code's comments name
(`lib/...`) are found.
