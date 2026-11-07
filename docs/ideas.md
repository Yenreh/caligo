# Ideas

Possible features, looked into but not planned yet. Findings checked on
2026-09-29.

## The route network in one file, or bundled

The planner reads 91 route files, 555 KB as the service sends them (it
does not compress). Compacted into one file the whole network takes
145 KB, 39 KB gzipped: faster to load, and small enough to ship with the
APK, so the planner works on first use without its 92 requests. A
bundled copy ages between releases, so the background refresh stays.

## Updating from inside the app

Today the app asks GitHub for the latest release only when told to, and
opens its APK in the browser: it downloads, the file is opened, and
Android installs it over the old one, asking the first time to let the
browser install apps. The app holds no permission to install anything,
on purpose.

The app could download the APK itself and hand it to Android's installer
(`PackageInstaller`, what stores use): **Update**, a progress bar, and
Android's "Update this app?" dialog; the first time, Android asks to let
CaliGo install apps. It takes the `REQUEST_INSTALL_PACKAGES` permission
and about 80 lines of Kotlin, the download with OkHttp, which the app
already has: no new libraries, nothing paid. Android only takes an
update signed with the same key, so a tampered APK cannot replace the
app, but the app could then install things, which the current design
avoids. It still asks to confirm: from Android 12 an app that installed
itself can sometimes skip that, not reliably.

Other ways, for reference: Google Play gives truly automatic updates for
US$25 once, but a new personal account needs 12 testers opted in for 14
days in a row before publishing. Obtainium follows GitHub releases for
whoever installs it. F-Droid and IzzyOnDroid are free catalogs with
updates, after a review; F-Droid builds the app itself, without the
CARTO key, so its map is OpenStreetMap's.
