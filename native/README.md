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
app's data as is. Settings, Import data takes a backup exported by the
Flutter app (Settings, Export data) through the system's document picker;
Export data hands one out through the share sheet. A debug build also
takes one on launch, without the screen:

```sh
adb push caligo_backup.json /sdcard/Android/data/com.yenreh.caligo.next/files/import.json
```

It is imported once and renamed to `import.done.json`; one that fails to
import stays, and is tried again on the next launch. An import is all or
nothing: a file with a fault changes nothing.

## Location

The framework's `LocationManager`, no Play services, behind
`data/location/LocationSource` so tests use a fake: coarse or fine while in
use, asked for from `MainActivity` the first time the add screen's "Near
me" tab shows. The stops screen and the home put the closest stops first
when the setting asks for it and the permission is already given; they
never ask for it.

## Map

`ui/map/LabMap` draws every map: raster tiles, markers and lines, with
pan, pinch, fling, double tap and, where the screen allows them,
two-finger turns (a line's route stays north up). It stands on
MapCompose, which draws the tiles with Compose's own canvas (no native
code); screens use `LabMap` and `LabMapState` only, so another library
would change that one file.

Tiles come from CARTO when the build carries a key, from OpenStreetMap
otherwise, as the Flutter app's. The key is limited to CaliGo's package
signed with its release certificate, which every tile request names
(`AppInfo.PACKAGE_ID`, `AppInfo.SIGNING_SHA1`). It comes from the
`CARTO_KEY` environment variable or, for local builds, from
`local.properties`, which git ignores:

```properties
carto.key=...
```

Never commit it. The tiles are kept in `cache/fm_cache`, in the files
flutter_map wrote, up to 100 MB; Settings, Cached data counts and clears
them with the rest.

## Trip planner

`domain/TripPlanner` plans on every route as one network, which
`ui/trips/TransitNetworkLoader` gathers once a run, when a planner screen
first needs it: four routes at a time, week-old copies answering at once
while new ones come behind them. A plan runs off the main thread, as the
Flutter app ran it in an isolate. Addresses and places are looked up with
the framework `Geocoder` (`data/datasources/DeviceGeocoder`, behind an
interface for tests), in Colombian Spanish and within Cali; a phone
without one gets the MIO stops named after a crossing only.

The planner opens from the home's bar, or from a stop's "Go here" with
the stop as where to go (`Route.PlanTrip`). Each end opens the place
picker (`Route.PlacePicker`), which opens the map point picker
(`Route.MapPoint`) in turn. Navigation 3 has no results of its own: a
picker leaves its choice in `ui/trips/PickedPlaces`, saved with the back
stack in `AppNavigation`, and pops itself; the screen below takes it as
it shows again. Through a rotation every screen keeps its view model; after
the process dies the back stack, what was typed and a choice still on its
way come back, while the planner starts again from its key and the device.

## Licenses

The licenses page lists the app's GPL and the fonts' OFL, from
`app/src/main/assets/licenses/`, and every library in the APK, from the
report the AboutLibraries Gradle plugin writes at build time
(`res/raw/aboutlibraries.json`, offline, BOMs left out). Offline, the
plugin writes no licence text of its own: the texts come from
`app/config/licenses/`, one file per SPDX id (Apache-2.0 for every
library today); a library under another licence needs its file there. No runtime
library reads it: the app parses the JSON itself.

## Baseline Profile

The `baselineprofile` module cold-starts the app to the home with its
content and scrolls it. With the phone connected (Android 13 or later) and
data seeded through a debug build as above:

```sh
./gradlew :app:generateBaselineProfile
```

The run installs its own build of the app over the one there, and
instrumented runs may uninstall the app when they end: seed again after.
The profile lands in `app/src/release/generated/baselineProfiles/`, to be
committed; the release build packs it, and `profileinstaller` installs it
where no app store does.
