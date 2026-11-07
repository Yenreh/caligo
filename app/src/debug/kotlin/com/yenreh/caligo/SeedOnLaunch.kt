package com.yenreh.caligo

import android.util.Log
import java.io.File

private const val TAG = "CaliGoSeed"

/**
 * Debug builds only: a backup pushed to the external files folder as
 * `import.json` is imported once at launch, before the first screen reads
 * anything, and renamed to `import.done.json`. A file that fails to
 * import stays, to be tried again on the next launch.
 *
 * `adb push backup.json /sdcard/Android/data/com.yenreh.caligo.next/files/import.json`
 */
internal fun seedOnLaunch(app: CaliGoApp) {
    val folder = app.getExternalFilesDir(null) ?: return
    val file = File(folder, "import.json")
    if (!file.exists()) return

    app.container.beforeFirstScreen {
        try {
            val imported = app.container.backup.import(file.readText())
            file.renameTo(File(folder, "import.done.json"))
            Log.i(TAG, "Imported $imported cards and stops from ${file.path}")
        } catch (e: Exception) {
            Log.e(TAG, "Could not import ${file.path}", e)
        }
    }
}
